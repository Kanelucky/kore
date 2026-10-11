package org.koremc.network.rtc

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CLIENT_FINGERPRINT =
    "sha-256 3C:91:0E:5A:D2:47:B8:16:6F:E0:29:C4:7B:5D:A3:18:90:EE:24:6C:D1:0F:B7:52:8A:3E:C9:64:1D:F2:07:AB"

internal val SAMPLE_OFFER = listOf(
    "v=0",
    "o=- 4611731400430051336 2 IN IP4 127.0.0.1",
    "s=-",
    "t=0 0",
    "a=group:BUNDLE 0",
    "a=fingerprint:$CLIENT_FINGERPRINT",
    "a=identity:eyJpZHAiOnt9fQ==",
    "a=extmap-allow-mixed",
    "a=msid-semantic: WMS",
    "m=application 9 UDP/DTLS/SCTP webrtc-datachannel",
    "c=IN IP4 0.0.0.0",
    "a=candidate:1 1 udp 2122260223 192.168.1.20 52000 typ host generation 0",
    "a=ice-ufrag:Qx7t",
    "a=ice-pwd:9fJk2LmN8pQr4StU6vWx0yZa",
    "a=ice-options:trickle",
    "a=setup:actpass",
    "a=mid:0",
    "a=sctp-port:5000",
    "a=max-message-size:262144",
).joinToString("\r\n", postfix = "\r\n")

class SdpTest {
    @Test
    fun `offer fields are parsed from both session and media level`() {
        val offer = parseOffer(SAMPLE_OFFER)

        assertEquals("0", offer.mid)
        assertEquals("Qx7t", offer.iceUfrag)
        assertEquals("9fJk2LmN8pQr4StU6vWx0yZa", offer.icePwd)
        assertEquals(listOf(Fingerprint.parse(CLIENT_FINGERPRINT)), offer.fingerprints)
        assertEquals("actpass", offer.setup)
        assertEquals(5000, offer.sctpPort)
        assertEquals(262144L, offer.maxMessageSize)
        assertEquals(1, offer.candidates.size)
        assertEquals("eyJpZHAiOnt9fQ==", offer.identity)
    }

    @Test
    fun `media level attributes override session level ones`() {
        val mediaFingerprint = "sha-256 AA:BB"
        val offer = parseOffer(SAMPLE_OFFER.replace("a=mid:0", "a=mid:0\r\na=fingerprint:$mediaFingerprint"))

        assertEquals(listOf(Fingerprint.parse(mediaFingerprint)), offer.fingerprints)
    }

    @Test
    fun `identity is optional`() {
        assertNull(parseOffer(SAMPLE_OFFER.replace(Regex("a=identity:.*\r\n"), "")).identity)
    }

    @Test
    fun `offers that are not data channels are rejected`() {
        assertFailsWith<InvalidOfferException> {
            parseOffer(SAMPLE_OFFER.replace("m=application 9 UDP/DTLS/SCTP webrtc-datachannel", "m=audio 9 UDP/TLS/RTP/SAVPF 111"))
        }
    }

    @Test
    fun `offers missing ice credentials are rejected`() {
        assertFailsWith<InvalidOfferException> { parseOffer(SAMPLE_OFFER.replace("a=ice-ufrag:Qx7t\r\n", "")) }
    }

    @Test
    fun `unknown dtls roles are rejected`() {
        assertFailsWith<InvalidOfferException> { parseOffer(SAMPLE_OFFER.replace("a=setup:actpass", "a=setup:holdconn")) }
    }

    @Test
    fun `answer keeps identity after fingerprint and before the media section`() {
        val answer = SdpAnswer(
            sessionId = 42,
            mid = "0",
            ice = IceCredentials("abcd", "abcdefghijklmnopqrstuvwx"),
            fingerprint = Fingerprint.parse(CLIENT_FINGERPRINT),
            identity = "e30=",
            setup = "passive",
            sctpPort = 5000,
            maxMessageSize = 262144,
            candidates = hostCandidates(19132, listOf(InetAddress.getByName("203.0.113.7"))),
        ).encode()
        val lines = answer.removeSuffix("\r\n").split("\r\n")

        assertTrue(answer.endsWith("\r\n") && !answer.contains(Regex("[^\r]\n")), "SDP lines must end in CRLF")
        assertTrue(lines.indexOf("a=fingerprint:$CLIENT_FINGERPRINT") < lines.indexOf("a=identity:e30="))
        assertTrue(lines.indexOf("a=identity:e30=") < lines.indexOfFirst { it.startsWith("m=") })
        assertTrue("a=ice-lite" in lines)
        assertTrue("a=candidate:1 1 udp 2130706431 203.0.113.7 19132 typ host" in lines)
        assertEquals("a=end-of-candidates", lines.last())
    }

    @Test
    fun `sendable message size follows the offer within kore's limit`() {
        val offer = parseOffer(SAMPLE_OFFER)

        assertEquals(262144, offer.maxSendableMessageSize)
        assertEquals(65536, offer.copy(maxMessageSize = null).maxSendableMessageSize)
        assertEquals(262144, offer.copy(maxMessageSize = 0).maxSendableMessageSize)
        assertEquals(1200, offer.copy(maxMessageSize = 1200).maxSendableMessageSize)
        assertEquals(262144, offer.copy(maxMessageSize = 1L shl 40).maxSendableMessageSize)
    }

    @Test
    fun `answers round trip through the offer parser`() {
        val local = IceCredentials.random()
        val answer = SdpAnswer(
            sessionId = 1,
            mid = "0",
            ice = local,
            fingerprint = Fingerprint.parse(CLIENT_FINGERPRINT),
            identity = "e30=",
            setup = "passive",
            sctpPort = 5000,
            maxMessageSize = 262144,
            candidates = emptyList(),
        )
        val parsed = parseOffer(answer.encode())

        assertEquals(local.ufrag, parsed.iceUfrag)
        assertEquals(local.pwd, parsed.icePwd)
        assertEquals("passive", parsed.setup)
    }
}
