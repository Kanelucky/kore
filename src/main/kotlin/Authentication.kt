package org.koremc

import org.koremc.auth.JoseException
import org.koremc.auth.Jws
import org.koremc.auth.MultiplayerTokenVerifier
import org.koremc.auth.sameKey
import org.koremc.protocol.LoginRequest
import org.koremc.protocol.PacketException
import org.koremc.protocol.playerUuid
import java.security.interfaces.ECPublicKey
import java.util.UUID

/** Thrown when a login doesn't prove who it claims to be. */
class AuthenticationException(message: String) : Exception(message)

/** A player whose login was accepted. */
data class PlayerIdentity(
    val displayName: String,
    /** Null for players who aren't signed in to Xbox Live. */
    val xuid: String?,
    val uuid: UUID,
    val publicKey: ECPublicKey,
    /** Whether Minecraft's authorization service vouched for this identity. Always true in online mode. */
    val verified: Boolean,
)

/**
 * Decides whether a login proves who it claims to be:
 *
 * 1. Its multiplayer token verifies against Minecraft's authorization
 *    service. Online mode requires that. Offline mode falls back to the
 *    identity the client claims when it doesn't.
 * 2. Its client data is signed by the key that identity names.
 * 3. That key is the one the client proved in its offer, so the login can't
 *    have been lifted from another connection.
 * 4. In online mode, the player is signed in to Xbox Live.
 */
class LoginAuthenticator(private val tokens: MultiplayerTokenVerifier, private val onlineMode: Boolean) {
    fun authenticate(login: LoginRequest, offerKey: ECPublicKey?): PlayerIdentity {
        val player = verifiedIdentity(login) ?: if (onlineMode) {
            throw AuthenticationException("login has no multiplayer token")
        } else {
            login.identity.let { PlayerIdentity(it.displayName, it.xuid, it.uuid, it.publicKey, verified = false) }
        }

        val clientDataSigned = try {
            Jws.parse(login.clientDataToken).verify(player.publicKey, "ES384")
        } catch (e: JoseException) {
            false
        }
        if (!clientDataSigned) throw AuthenticationException("client data isn't signed by the login's key")

        if (offerKey != null && !sameKey(offerKey, player.publicKey)) {
            throw AuthenticationException("login key isn't the one this connection proved in its offer")
        }
        if (onlineMode && player.xuid == null) throw AuthenticationException("player isn't signed in to Xbox Live")
        return player
    }

    /** The identity in the login's token, or null if offline mode lets an unverifiable token through. */
    private fun verifiedIdentity(login: LoginRequest): PlayerIdentity? {
        val token = login.token ?: return null
        val verified = try {
            tokens.verify(token)
        } catch (e: JoseException) {
            if (onlineMode) throw AuthenticationException("multiplayer token didn't verify: ${e.message}")
            return null
        }
        val uuid = try {
            playerUuid(verified.xuid, verified.legacyUuid)
        } catch (e: PacketException) {
            throw AuthenticationException(e.message ?: "token names no player")
        }
        return PlayerIdentity(verified.displayName, verified.xuid, uuid, verified.publicKey, verified = true)
    }
}
