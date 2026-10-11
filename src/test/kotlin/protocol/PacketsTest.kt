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
    fun `an empty resource pack list is four flags, a blank template and no packs`() {
        val expected = bytes(0x06, 0x00, 0x00, 0x00, 0x00) + ByteArray(16) + bytes(0x00, 0x00)

        assertContentEquals(expected, ResourcePacksInfo().encode())
    }

    @Test
    fun `the pack stack counts experiments with a plain uint32`() {
        assertContentEquals(bytes(0x07, 0x00, 0x00, 0x01, '*'.code, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00), ResourcePackStack().encode())
        assertContentEquals(
            bytes(0x07, 0x00, 0x00, 0x01, '*'.code, 0x01, 0x00, 0x00, 0x00) + bytes(0x07) + "cameras".toByteArray() + bytes(0x01, 0x01, 0x00),
            ResourcePackStack(experiments = mapOf("cameras" to true)).encode(),
        )
    }

    @Test
    fun `resource pack responses carry a tag and its name`() {
        val finished = bytes(0x08, 0x02, 19) + "downloadingfinished".toByteArray()
        val downloading = bytes(0x08, 0x01, 11) + "downloading".toByteArray() + bytes(0x01, 0x03) + "a-b".toByteArray()

        assertEquals(ResourcePackClientResponse(ResourcePackClientResponse.Response.DOWNLOADING_FINISHED), decodeClientPacket(finished))
        assertEquals(ResourcePackClientResponse(ResourcePackClientResponse.Response.DOWNLOADING, listOf("a-b")), decodeClientPacket(downloading))
        assertFailsWith<PacketException> { decodeClientPacket(bytes(0x08, 0x09, 0x00)) }
    }

    @Test
    fun `client cache status is a single flag`() {
        assertEquals(ClientCacheStatus(supported = true), decodeClientPacket(bytes(0x81, 0x01, 0x01)))
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
