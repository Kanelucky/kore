package org.koremc.protocol

import io.netty.buffer.Unpooled
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.util.Base64
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
 * Who the client says it is. Nothing here is verified yet: it's read
 * straight out of tokens whose signatures Kore hasn't checked, so it must
 * not be trusted until authentication is in place.
 */
data class ClaimedIdentity(
    val displayName: String,
    /** Null for clients that aren't signed in to Xbox Live. */
    val xuid: String?,
    val uuid: UUID,
    /** The client's public key (base64 DER), which signs its client data. */
    val publicKey: String,
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
    /** The raw tokens, kept for verification later. */
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

    val authentication = parseJsonObject(auth)
    val certificate = authentication.string("Certificate")?.let(::parseJsonObject) ?: authentication
    val chain = (certificate["chain"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: throw PacketException("chain holds a non-string") }.orEmpty()
    val token = authentication.string("Token")?.takeIf { it.isNotEmpty() }

    return LoginRequest(
        authenticationType = (authentication["AuthenticationType"] as? JsonPrimitive)?.intOrNull?.let { AuthenticationType.entries.getOrNull(it) },
        identity = if (token != null) identityFromToken(jwtClaims(token)) else identityFromChain(chain),
        clientData = jwtClaims(clientDataToken),
        chain = chain,
        token = token,
        clientDataToken = clientDataToken,
    )
}

/** The current token: `xid` and `xname` for Xbox players, `leguuid` for offline ones, and `cpk` for the client's key. */
private fun identityFromToken(claims: JsonObject): ClaimedIdentity {
    val xuid = claims.string("xid")?.takeIf { it.isNotEmpty() }
    val uuid = claims.string("leguuid")?.let(::parseUuid)
        ?: xuid?.let(::uuidFromXuid)
        ?: throw PacketException("token has neither an XUID nor a UUID")
    return ClaimedIdentity(
        displayName = claims.string("xname") ?: throw PacketException("token has no display name"),
        xuid = xuid,
        uuid = uuid,
        publicKey = claims.string("cpk") ?: throw PacketException("token has no client public key"),
    )
}

/** The legacy chain: the last token's `extraData` names the player, and its `identityPublicKey` is the client's key. */
private fun identityFromChain(chain: List<String>): ClaimedIdentity {
    val claims = jwtClaims(chain.lastOrNull() ?: throw PacketException("connection request has no token and an empty chain"))
    val extraData = claims["extraData"] as? JsonObject ?: throw PacketException("identity claim has no extraData")
    return ClaimedIdentity(
        displayName = extraData.string("displayName") ?: throw PacketException("identity claim has no display name"),
        xuid = extraData.string("XUID")?.takeIf { it.isNotEmpty() },
        uuid = parseUuid(extraData.string("identity") ?: throw PacketException("identity claim has no UUID")),
        publicKey = claims.string("identityPublicKey") ?: throw PacketException("identity claim has no public key"),
    )
}

/**
 * Bedrock derives a player's UUID from their XUID as an MD5 name-based
 * (version 3) UUID of `pocket-auth-1-xuid:<xuid>`, which is exactly what
 * [UUID.nameUUIDFromBytes] computes.
 */
fun uuidFromXuid(xuid: String): UUID = UUID.nameUUIDFromBytes("pocket-auth-1-xuid:$xuid".toByteArray())

/** A JWT's claims, without checking its signature. */
private fun jwtClaims(jwt: String): JsonObject {
    val parts = jwt.split('.')
    if (parts.size != 3) throw PacketException("token isn't a JWT")
    val payload = try {
        Base64.getUrlDecoder().decode(parts[1])
    } catch (e: IllegalArgumentException) {
        throw PacketException("token payload isn't base64url")
    }
    return parseJsonObject(payload.decodeToString())
}

private fun parseJsonObject(json: String): JsonObject = try {
    Json.parseToJsonElement(json).jsonObject
} catch (e: SerializationException) {
    throw PacketException("malformed JSON: ${e.message}")
} catch (e: IllegalArgumentException) {
    throw PacketException("expected a JSON object")
}

private fun parseUuid(value: String): UUID = try {
    UUID.fromString(value)
} catch (e: IllegalArgumentException) {
    throw PacketException("'$value' isn't a UUID")
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
