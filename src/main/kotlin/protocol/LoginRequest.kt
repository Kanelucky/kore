package org.koremc.protocol

import io.netty.buffer.Unpooled
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.koremc.auth.JoseException
import org.koremc.auth.Jws
import org.koremc.auth.ecKeyFromDer
import org.koremc.auth.parseClientKey
import org.koremc.auth.parseJsonObject
import org.koremc.auth.string
import java.security.interfaces.ECPublicKey
import java.util.UUID

/** How the client signed in. */
enum class AuthenticationType {
    /** Signed in to Xbox Live. */
    FULL,
    GUEST,

    /** Offline: the client signed its own identity. */
    SELF_SIGNED,
}

/**
 * Who the client says it is. Nothing here is verified: it's read straight
 * out of tokens whose signatures haven't been checked, so it must not be
 * trusted until a [LoginRequest] has been authenticated.
 */
data class ClaimedIdentity(
    val displayName: String,
    /** Null for clients that aren't signed in to Xbox Live. */
    val xuid: String?,
    val uuid: UUID,
    /** The client's key, which signs its client data. */
    val publicKey: ECPublicKey,
)

/** The parts of a client's settings worth having before the full client data is needed. */
data class ClientInfo(
    val gameVersion: String?,
    val deviceOs: Int?,
    val deviceModel: String?,
    val languageCode: String?,
)

/** A decoded connection request: the identity it claims, and the client data that came with it. */
class LoginRequest(
    val authenticationType: AuthenticationType?,
    val identity: ClaimedIdentity,
    /** Every claim in the client data token, skin included. Unverified, like the identity. */
    val clientData: JsonObject,
    /** The raw tokens, for authentication. */
    val chain: List<String>,
    val token: String?,
    val clientDataToken: String,
) {
    val clientInfo
        get() = ClientInfo(
            gameVersion = clientData.string("GameVersion"),
            deviceOs = (clientData["DeviceOS"] as? JsonPrimitive)?.intOrNull,
            deviceModel = clientData.string("DeviceModel"),
            languageCode = clientData.string("LanguageCode"),
        )
}

/**
 * Decodes the connection request inside a Login packet: two UTF-8 strings,
 * each behind a little-endian int32 length.
 *
 * The first is JSON about who the client is. Current clients send
 * `{"AuthenticationType": n, "Certificate": "<JSON with the chain>", "Token": "<JWT>"}`,
 * where the token carries the identity. Older ones sent `{"chain": [...]}` on
 * its own, with the identity in the chain's last token. The second string is
 * a JWT holding the client data: skin, device, language and so on.
 */
fun parseConnectionRequest(bytes: ByteArray): LoginRequest {
    val buffer = Unpooled.wrappedBuffer(bytes)
    val (auth, clientDataToken) = try {
        buffer.readIntLEString() to buffer.readIntLEString()
    } catch (e: IndexOutOfBoundsException) {
        throw PacketException("connection request ends early")
    }

    return decoding {
        val authentication = parseJsonObject(auth)
        val certificate = authentication.string("Certificate")?.let(::parseJsonObject) ?: authentication
        val chain = (certificate["chain"] as? JsonArray)
            ?.map { (it as? JsonPrimitive)?.contentOrNull ?: throw PacketException("chain holds a non-string") }
            .orEmpty()
        val token = authentication.string("Token")?.takeIf { it.isNotEmpty() }

        LoginRequest(
            authenticationType = (authentication["AuthenticationType"] as? JsonPrimitive)?.intOrNull?.let { AuthenticationType.entries.getOrNull(it) },
            identity = if (token != null) identityFromToken(Jws.parse(token).claims) else identityFromChain(chain),
            clientData = Jws.parse(clientDataToken).claims,
            chain = chain,
            token = token,
            clientDataToken = clientDataToken,
        )
    }
}

/** The current token: `xid` and `xname` for Xbox players, `leguuid` for offline ones, and `cpk` for the client's key. */
private fun identityFromToken(claims: JsonObject): ClaimedIdentity {
    val xuid = claims.string("xid")?.takeIf { it.isNotEmpty() }
    val legacyUuid = claims.string("leguuid")
    return ClaimedIdentity(
        displayName = claims.string("xname") ?: throw PacketException("token has no display name"),
        xuid = xuid,
        // A token naming neither still decodes, with a fresh UUID. Whether to admit it is authentication's call.
        uuid = if (xuid == null && legacyUuid == null) UUID.randomUUID() else playerUuid(xuid, legacyUuid),
        publicKey = parseClientKey(claims["cpk"]),
    )
}

/** The legacy chain: the last token's `extraData` names the player, and its `identityPublicKey` is the client's key. */
private fun identityFromChain(chain: List<String>): ClaimedIdentity {
    val claims = Jws.parse(chain.lastOrNull() ?: throw PacketException("connection request has no token and an empty chain")).claims
    val extraData = claims["extraData"] as? JsonObject ?: throw PacketException("identity claim has no extraData")
    val xuid = extraData.string("XUID")?.takeIf { it.isNotEmpty() }
    return ClaimedIdentity(
        displayName = extraData.string("displayName") ?: throw PacketException("identity claim has no display name"),
        xuid = xuid,
        uuid = playerUuid(xuid, explicit = extraData.string("identity")),
        publicKey = ecKeyFromDer(claims.string("identityPublicKey") ?: throw PacketException("identity claim has no public key")),
    )
}

/** A player's UUID: the one the token names, or else the one derived from their XUID. */
fun playerUuid(xuid: String?, explicit: String?): UUID {
    if (explicit != null) {
        return try {
            UUID.fromString(explicit)
        } catch (e: IllegalArgumentException) {
            throw PacketException("'$explicit' isn't a UUID")
        }
    }
    return uuidFromXuid(xuid ?: throw PacketException("login has neither an XUID nor a UUID"))
}

/**
 * Bedrock derives a player's UUID from their XUID as an MD5 name-based
 * (version 3) UUID of `pocket-auth-1-xuid:<xuid>`, which is exactly what
 * [UUID.nameUUIDFromBytes] computes.
 */
fun uuidFromXuid(xuid: String): UUID = UUID.nameUUIDFromBytes("pocket-auth-1-xuid:$xuid".toByteArray())

/** Reports malformed tokens and keys as packet errors, since they arrived in a packet. */
private inline fun <T> decoding(block: () -> T): T = try {
    block()
} catch (e: JoseException) {
    throw PacketException(e.message ?: "malformed login")
}
