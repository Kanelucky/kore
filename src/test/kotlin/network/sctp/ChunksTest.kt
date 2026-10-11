package org.koremc.network.sctp

import java.nio.ByteBuffer
import java.util.zip.CRC32C
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChunksTest {
    @Test
    fun `chunks survive a round trip`() {
        val chunks = listOf(
            DataChunk(Tsn(-2), 3, 7, 53, "hello".toByteArray(), unordered = true, beginning = true, ending = false),
            Init(true, 0x1234, 65536, 10, 20, Tsn(99), listOf(Parameter(7, byteArrayOf(1, 2, 3)), Parameter(0xC000, ByteArray(0)))),
            Sack(Tsn(500), 4096, listOf(2..3, 6..6), listOf(Tsn(498))),
            Heartbeat(ack = true, info = byteArrayOf(0, 1, 0, 6, 9, 9)),
            Shutdown(Tsn(42)),
            CookieEcho(byteArrayOf(4, 5, 6, 7, 8)),
            ForwardTsn(Tsn(77), listOf(0 to 5, 2 to 9)),
            CookieAck,
            OtherChunk(type = 0x99, flags = 0x01, value = byteArrayOf(1)),
        )
        val parsed = assertNotNull(parsePacket(SctpPacket(0xABCD, chunks).encode())).chunks

        val data = assertIs<DataChunk>(parsed[0])
        assertEquals(Tsn(-2), data.tsn)
        assertEquals(listOf(3, 7, 53), listOf(data.streamId, data.ssn, data.ppid))
        assertContentEquals("hello".toByteArray(), data.payload)
        assertTrue(data.unordered && data.beginning && !data.ending)

        val init = assertIs<Init>(parsed[1])
        assertTrue(init.ack)
        assertContentEquals(byteArrayOf(1, 2, 3), init.parameter(7))
        assertContentEquals(ByteArray(0), init.parameter(0xC000))

        val sack = assertIs<Sack>(parsed[2])
        assertEquals(listOf(2..3, 6..6), sack.gaps)
        assertEquals(listOf(Tsn(498)), sack.duplicates)

        assertContentEquals(byteArrayOf(0, 1, 0, 6, 9, 9), assertIs<Heartbeat>(parsed[3]).info)
        assertEquals(Tsn(42), assertIs<Shutdown>(parsed[4]).cumulativeTsn)
        assertContentEquals(byteArrayOf(4, 5, 6, 7, 8), assertIs<CookieEcho>(parsed[5]).cookie)
        assertEquals(listOf(0 to 5, 2 to 9), assertIs<ForwardTsn>(parsed[6]).streams)
        assertEquals(CookieAck, parsed[7])
        assertEquals(0x99, assertIs<OtherChunk>(parsed[8]).type)
    }

    @Test
    fun `checksum is crc32c stored least significant byte first`() {
        val packet = SctpPacket(1, listOf(CookieAck)).encode()
        val bytes = ByteArray(packet.remaining()).also { packet.get(it) }
        val stored = bytes.copyOfRange(8, 12)

        bytes.fill(0, 8, 12)
        val crc = CRC32C().apply { update(bytes) }.value.toInt()
        assertContentEquals(ByteBuffer.allocate(4).putInt(Integer.reverseBytes(crc)).array(), stored)
    }

    @Test
    fun `corrupted packets are rejected`() {
        val packet = SctpPacket(1, listOf(DataChunk(Tsn(1), 0, 0, 53, byteArrayOf(1, 2, 3)))).encode()
        packet.put(packet.limit() - 2, 9)

        assertNull(parsePacket(packet))
    }

    @Test
    fun `chunks are padded to four bytes but lengths are not`() {
        val packet = SctpPacket(1, listOf(DataChunk(Tsn(1), 0, 0, 53, ByteArray(5)), CookieAck)).encode()

        assertEquals(12 + 24 + 4, packet.remaining())
        assertEquals(21, packet.getShort(12 + 2).toInt())
    }

    @Test
    fun `tsns compare across the wrap`() {
        assertTrue(Tsn(-1) < Tsn(0))
        assertTrue(Tsn(Int.MAX_VALUE) < Tsn(Int.MIN_VALUE))
        assertEquals(Tsn(0), Tsn(-1) + 1)
        assertEquals("4294967295", Tsn(-1).toString())
    }
}
