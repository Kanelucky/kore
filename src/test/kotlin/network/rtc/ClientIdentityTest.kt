package org.koremc.network.rtc

import org.koremc.auth.ecKeyPair
import org.koremc.auth.sameKey
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

class ClientIdentityTest {
    // A client's identity has the same shape as Kore's own: a token naming its key as a JWK, and a
    // detached ES384 signature over the DTLS fingerprints. So Kore's signer can stand in for a client.
    private val clientKeys = ecKeyPair()
    private val client = OperatorIdentity(clientKeys, domain = "authorization.franchise.minecraft-services.net", issuer = "test client")
    private val offer = parseOffer(SAMPLE_OFFER)

    @Test
    fun `the key that signed the offer's fingerprints is the one returned`() {
        val signed = offer.copy(identity = client.assert(offer.fingerprints))

        assertTrue(sameKey(clientKeys.public, assertNotNull(signed.verifiedClientKey(requireIdentity = true))))
    }

    @Test
    fun `an identity signed for other fingerprints is rejected`() {
        val elsewhere = listOf(Fingerprint("sha-256", "AA:BB:CC"))

        assertFailsWith<InvalidOfferException> {
            offer.copy(identity = client.assert(elsewhere)).verifiedClientKey(requireIdentity = true)
        }
    }

    @Test
    fun `an expired token is rejected`() {
        val stale = client.assert(offer.fingerprints, now = Instant.now() - 2.hours.toJavaDuration())

        assertFailsWith<InvalidOfferException> { offer.copy(identity = stale).verifiedClientKey(requireIdentity = true) }
    }

    @Test
    fun `offers without an identity pass only when identity isn't required`() {
        val anonymous = offer.copy(identity = null)

        assertNull(anonymous.verifiedClientKey(requireIdentity = false))
        assertFailsWith<InvalidOfferException> { anonymous.verifiedClientKey(requireIdentity = true) }
    }

    @Test
    fun `a malformed identity is rejected even when identity is optional`() {
        // The sample offer's identity is just {"idp":{}}, with no assertion.
        assertFailsWith<InvalidOfferException> { offer.verifiedClientKey(requireIdentity = false) }
        assertFailsWith<InvalidOfferException> { offer.copy(identity = "%%%").verifiedClientKey(requireIdentity = false) }
    }
}
