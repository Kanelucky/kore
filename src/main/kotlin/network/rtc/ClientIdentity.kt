package org.koremc.network.rtc

import kotlinx.serialization.json.JsonObject
import org.koremc.auth.JoseException
import org.koremc.auth.Jws
import org.koremc.auth.checkLifetime
import org.koremc.auth.parseClientKey
import org.koremc.auth.parseJsonObject
import org.koremc.auth.string
import java.security.interfaces.ECPublicKey
import java.time.Instant
import java.util.Base64

/**
 * The key a client proved in its offer, or null if the offer carried no
 * identity and [requireIdentity] is off.
 *
 * A client's `a=identity` (Mojang's NetherNet onboarding guide, §5.1) holds
 * its multiplayer token and a detached ES384 signature over the offer's DTLS
 * fingerprints, made with the private half of the token's `cpk`. Checking
 * that signature binds this connection to that key, and nothing more: the
 * token itself is verified when it arrives again in the Login packet, which
 * must carry this same key. That's what stops a login lifted from one
 * connection being replayed on another.
 */
fun SdpOffer.verifiedClientKey(requireIdentity: Boolean, now: Instant = Instant.now()): ECPublicKey? {
    val identity = identity ?: if (requireIdentity) throw InvalidOfferException("offer has no identity") else return null
    return try {
        verifyIdentity(identity, fingerprints, now)
    } catch (e: JoseException) {
        throw InvalidOfferException("identity doesn't check out: ${e.message}")
    }
}

private fun verifyIdentity(identity: String, fingerprints: List<Fingerprint>, now: Instant): ECPublicKey {
    val envelope = parseJsonObject(decodeBase64(identity).decodeToString())
    if ((envelope["idp"] as? JsonObject)?.string("protocol") != "default") {
        throw JoseException("identity provider protocol isn't 'default'")
    }
    val assertion = parseJsonObject(envelope.string("assertion") ?: throw JoseException("identity has no assertion"))

    val token = Jws.parse(assertion.string("token") ?: throw JoseException("assertion has no token"))
    checkLifetime(token.claims, now, requireExpiry = false)
    val key = parseClientKey(token.claims["cpk"])

    val payload = canonicalFingerprints(fingerprints).toByteArray()
    val signature = Jws.parseDetached(assertion.string("fingerprints") ?: throw JoseException("assertion has no fingerprints"), payload)
    if (!signature.verify(key, "ES384")) throw JoseException("the token's key didn't sign this offer's fingerprints")
    return key
}

private fun decodeBase64(value: String): ByteArray = try {
    Base64.getDecoder().decode(value)
} catch (e: IllegalArgumentException) {
    try {
        Base64.getUrlDecoder().decode(value)
    } catch (e: IllegalArgumentException) {
        throw JoseException("identity isn't base64")
    }
}
