package org.koremc.network.rtc

import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RFC 5769 §2.1: a Binding request with USERNAME, ICE attributes, MESSAGE-INTEGRITY and FINGERPRINT. */
private val RFC_5769_REQUEST = HexFormat.of().parseHex(
    "000100582112a442b7e7a701bc34d686fa87dfae" +
        "802200105354554e207465737420636c69656e74" +
        "002400046e0001ff" +
        "80290008932ff9b151263b36" +
        "000600096576746a3a68367659202020" +
        "000800149aeaa70cbfd8cb56781ef2b5b2d3f249c1b571a2" +
        "80280004e57a3bcf",
)
private const val RFC_5769_PASSWORD = "VOkJxbRl1RmTxUk/WvJxBt"

class StunTest {
    @Test
    fun `rfc 5769 request parses and authenticates`() {
        val request = assertNotNull(parseStun(RFC_5769_REQUEST))

        assertTrue(request.isBindingRequest)
        assertEquals("evtj:h6vY", request.username)
        assertFalse(request.useCandidate)
        assertTrue(request.isAuthentic(RFC_5769_PASSWORD))
        assertFalse(request.isAuthentic("not the password"))
    }

    @Test
    fun `corrupted messages fail the fingerprint check`() {
        val corrupted = RFC_5769_REQUEST.copyOf().also { it[30] = (it[30] + 1).toByte() }

        assertNull(parseStun(corrupted))
    }

    @Test
    fun `messages without a fingerprint are rejected`() {
        assertNull(parseStun(RFC_5769_REQUEST.copyOf(RFC_5769_REQUEST.size - 8).also { it[3] = (it[3] - 8).toByte() }))
    }

    @Test
    fun `binding success reports the ipv4 sender`() {
        assertMapsBack(InetSocketAddress(InetAddress.getByName("192.0.2.1"), 32853))
    }

    @Test
    fun `binding success reports the ipv6 sender`() {
        assertMapsBack(InetSocketAddress(InetAddress.getByName("2001:db8:1234:5678:11:2233:4455:6677"), 32853))
    }

    private fun assertMapsBack(sender: InetSocketAddress) {
        val request = assertNotNull(parseStun(RFC_5769_REQUEST))
        val response = assertNotNull(parseStun(bindingSuccess(request, sender, RFC_5769_PASSWORD)))

        assertEquals(STUN_BINDING_SUCCESS, response.type)
        assertContentEquals(request.transactionId, response.transactionId)
        assertTrue(response.isAuthentic(RFC_5769_PASSWORD))
        assertEquals(sender, decodeXorAddress(assertNotNull(response.attributes[STUN_XOR_MAPPED_ADDRESS]), response.transactionId))
    }

    private fun decodeXorAddress(value: ByteArray, transactionId: ByteArray): InetSocketAddress {
        val key = ByteBuffer.allocate(16).putInt(0x2112A442).put(transactionId).array()
        val port = (ByteBuffer.wrap(value).getShort(2).toInt() and 0xFFFF) xor 0x2112
        val ip = ByteArray(value.size - 4) { (value[it + 4].toInt() xor key[it].toInt()).toByte() }
        return InetSocketAddress(InetAddress.getByAddress(ip), port)
    }
}
