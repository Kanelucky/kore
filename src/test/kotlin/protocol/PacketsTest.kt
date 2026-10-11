package org.koremc.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PacketsTest {
    @Test
    fun `request network settings carries a big-endian protocol`() {
        // ID 193 as a varuint, then 2193 as a big-endian int32.
        val packet = decodeClientPacket(bytes(0xC1, 0x01, 0x00, 0x00, 0x08, 0x91))

        assertEquals(RequestNetworkSettings(protocol = 2193), packet)
    }

    @Test
    fun `sub-client bits in the header are ignored`() {
        val header = 193 or (1 shl 10) or (2 shl 12)
        val packet = decodeClientPacket(bytes((header and 0x7F) or 0x80, header shr 7, 0, 0, 0x08, 0x91))

        assertEquals(RequestNetworkSettings(protocol = 2193), packet)
    }

    @Test
    fun `login carries a protocol and a connection request`() {
        val login = assertIs<Login>(decodeClientPacket(bytes(0x01, 0x00, 0x00, 0x08, 0x91, 0x03, 0x0A, 0x0B, 0x0C)))

        assertEquals(2193, login.protocol)
        assertContentEquals(bytes(0x0A, 0x0B, 0x0C), login.connectionRequest)
    }

    @Test
    fun `network settings encode little-endian`() {
        val encoded = NetworkSettings(compressionThreshold = 256).encode()

        // ID 143, threshold 256, zlib, throttle off, threshold 0, scalar 0.0f.
        assertContentEquals(bytes(0x8F, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00), encoded)
    }

    @Test
    fun `play status encodes big-endian`() {
        assertContentEquals(bytes(0x02, 0x00, 0x00, 0x00, 0x01), PlayStatus(PlayStatus.Status.LOGIN_FAILED_CLIENT_OLD).encode())
        assertContentEquals(bytes(0x02, 0x00, 0x00, 0x00, 0x02), PlayStatus(PlayStatus.Status.LOGIN_FAILED_SERVER_OLD).encode())
    }

    @Test
    fun `disconnect zigzags its reason and marks whether messages follow`() {
        assertContentEquals(bytes(0x05, 0x5C, 0x01), Disconnect(Disconnect.Reason.NOT_AUTHENTICATED).encode())
        assertContentEquals(
            bytes(0x05, 0x6E, 0x00, 0x02, 'H'.code, 'i'.code, 0x02, 'H'.code, 'i'.code),
            Disconnect(Disconnect.Reason.KICKED, message = "Hi").encode(),
        )
    }

    @Test
    fun `packets Kore doesn't decode yet pass through`() {
        val packet = assertIs<UnhandledPacket>(decodeClientPacket(bytes(0x05, 0x01, 0x02)))

        assertEquals(5, packet.id)
        assertContentEquals(bytes(0x01, 0x02), packet.payload)
    }

    @Test
    fun `truncated packets are rejected`() {
        assertFailsWith<PacketException> { decodeClientPacket(bytes(0xC1, 0x01, 0x00)) }
        assertFailsWith<PacketException> { decodeClientPacket(bytes(0x01, 0x00, 0x00, 0x08, 0x91, 0x05, 0x01)) }
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
