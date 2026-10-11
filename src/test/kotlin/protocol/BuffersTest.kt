package org.koremc.protocol

import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BuffersTest {
    @Test
    fun `varuints use seven bits per byte`() {
        assertContentEquals(byteArrayOf(0x00), encode { writeVarUInt(0) })
        assertContentEquals(byteArrayOf(0x7F), encode { writeVarUInt(127) })
        assertContentEquals(byteArrayOf(0x80.toByte(), 0x01), encode { writeVarUInt(128) })
        assertContentEquals(byteArrayOf(0xC1.toByte(), 0x01), encode { writeVarUInt(193) })
        assertContentEquals(byteArrayOf(-1, -1, -1, -1, 0x0F), encode { writeVarUInt(-1) })
    }

    @Test
    fun `varuints round trip`() {
        for (value in listOf(0, 1, 300, 2193, Int.MAX_VALUE, Int.MIN_VALUE, -1)) {
            assertEquals(value, Unpooled.wrappedBuffer(encode { writeVarUInt(value) }).readVarUInt())
        }
    }

    @Test
    fun `varints zigzag so small negatives stay short`() {
        assertContentEquals(byteArrayOf(0x01), encode { writeVarInt(-1) })
        assertContentEquals(byteArrayOf(0x02), encode { writeVarInt(1) })
        for (value in listOf(0, -1, 1, -64, 64, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals(value, Unpooled.wrappedBuffer(encode { writeVarInt(value) }).readVarInt())
        }
    }

    @Test
    fun `overlong varuints are rejected`() {
        assertFailsWith<PacketException> { Unpooled.wrappedBuffer(byteArrayOf(-1, -1, -1, -1, -1, 0)).readVarUInt() }
    }

    @Test
    fun `strings carry a varuint length`() {
        val bytes = encode { writeString("Kore") }

        assertContentEquals(byteArrayOf(4, 'K'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'e'.code.toByte()), bytes)
        assertEquals("Kore", Unpooled.wrappedBuffer(bytes).readString())
    }

    @Test
    fun `lengths past the end are rejected`() {
        assertFailsWith<PacketException> { Unpooled.wrappedBuffer(byteArrayOf(10, 1, 2)).readByteArray() }
        assertFailsWith<PacketException> { Unpooled.wrappedBuffer(byteArrayOf(-1, -1, -1, -1)).readIntLEString() }
    }

    private fun encode(write: io.netty.buffer.ByteBuf.() -> Unit) = ByteBufUtil.getBytes(Unpooled.buffer().apply(write))
}
