package org.koremc.protocol

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BatchTest {
    private val codec = BatchCodec()

    @Test
    fun `packets round trip uncompressed before network settings`() {
        val packets = listOf(byteArrayOf(1, 2, 3), ByteArray(0), ByteArray(300) { it.toByte() })
        val batch = codec.encode(packets)

        assertEquals(3, batch[0].toInt())
        assertContentEquals(packets.map { it.toList() }, codec.decode(batch).map { it.toList() })
    }

    @Test
    fun `small batches are marked uncompressed once compression is on`() {
        codec.enableCompression(threshold = 256)
        val batch = codec.encode(listOf(byteArrayOf(9)))

        assertContentEquals(byteArrayOf(0xFF.toByte(), 1, 9), batch)
        assertContentEquals(byteArrayOf(9), codec.decode(batch).single())
    }

    @Test
    fun `large batches are deflated`() {
        codec.enableCompression(threshold = 256)
        val packet = ByteArray(5000) { (it % 7).toByte() }
        val batch = codec.encode(listOf(packet))

        assertEquals(0x00, batch[0].toInt())
        assertTrue(batch.size < packet.size / 4)
        assertContentEquals(packet, codec.decode(batch).single())
    }

    @Test
    fun `batches that inflate past the limit are rejected`() {
        codec.enableCompression(threshold = 256)
        val bomb = byteArrayOf(0x00) + rawDeflate(ByteArray(17 shl 20))

        assertFailsWith<PacketException> { codec.decode(bomb) }
    }

    @Test
    fun `compression Kore didn't ask for is rejected`() {
        codec.enableCompression(threshold = 256)

        assertFailsWith<PacketException> { codec.decode(byteArrayOf(0x01, 0, 0)) }
        assertFailsWith<PacketException> { codec.decode(ByteArray(0)) }
    }

    @Test
    fun `batches that end inside a packet are rejected`() {
        assertFailsWith<PacketException> { codec.decode(byteArrayOf(5, 1, 2)) }
    }

    private fun rawDeflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true).apply {
            setInput(data)
            finish()
        }
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
        deflater.end()
        return out.toByteArray()
    }
}
