package org.koremc.protocol

import org.koremc.world.BlockPos
import org.koremc.world.Vec3
import kotlin.test.Test
import kotlin.test.assertContentEquals

class SoundsTest {
    /** The handle 7, as a little-endian long. */
    private val handle = bytes(0x07, 0, 0, 0, 0, 0, 0, 0)

    @Test
    fun `a sound played by a handle goes at its point in eighths, then plays once, in range, from its start`() {
        val sound = PlaySound("record.cat", Vec3(1.5f, -62.5f, 0.5f), 4f, 1f, handle = 7)

        val expected = bytes(0x56, 0x0A) + "record.cat".encodeToByteArray() +
            bytes(0x18, 0xE7, 0x07, 0x08) + // 12, -500 and 4, zigzagged
            bytes(0x00, 0x00, 0x80, 0x40, 0x00, 0x00, 0x80, 0x3F) + // Volume 4, pitch 1
            bytes(0x00, 0x00, 0x01) + handle + bytes(0x00) // Not again, not out of range, the handle, and no place to start from
        assertContentEquals(expected, sound.encode())
    }

    @Test
    fun `a record starting names its jukebox and the handle, and stopping names the handle alone`() {
        assertContentEquals(bytes(0xE0, 0x02, 0x02, 0x7D, 0x00) + handle, RecordStarted(BlockPos(1, -63, 0), 7).encode())
        // The handle, then the seven changes a sound can take, each stopping it, or nothing.
        assertContentEquals(bytes(0xDC, 0x02) + handle + ByteArray(7), UpdateSoundData(7).encode())
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
