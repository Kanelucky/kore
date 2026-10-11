package org.koremc.network.rtc

import java.security.MessageDigest
import java.util.Date
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class DtlsCertificateTest {
    private val generated = DtlsCertificate.generate()

    @Test
    fun `certificate is self-signed and currently valid`() {
        generated.certificate.verify(generated.keyPair.public)
        generated.certificate.checkValidity(Date())

        assertEquals(3, generated.certificate.version)
        assertEquals(generated.keyPair.public, generated.certificate.publicKey)
    }

    @Test
    fun `fingerprint is the sha-256 of the encoded certificate`() {
        val digest = MessageDigest.getInstance("SHA-256").digest(generated.certificate.encoded)

        assertEquals("sha-256", generated.fingerprint.algorithm)
        assertEquals(HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest), generated.fingerprint.digest)
    }
}
