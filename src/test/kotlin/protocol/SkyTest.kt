package org.koremc.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals

class SkyTest {
    @Test
    fun `the time goes as a zigzagged varint`() {
        // 24000, zigzagged to 48000.
        assertContentEquals(bytes(0x0A, 0x80, 0xF7, 0x02), SetTime(24000).encode())
    }

    @Test
    fun `game rules go by name, each on or off as a byte, or a number as a little-endian int, as the game starts too`() {
        val changed = GameRulesChanged(linkedMapOf("pvp" to false, "spawnradius" to 3)).encode()
        val expected = bytes(0x48, 0x02) +
            bytes(0x03) + "pvp".encodeToByteArray() + bytes(0x00, 0x01, 0x00) + // Not changed by players, on or off, and off
            bytes(0x0B) + "spawnradius".encodeToByteArray() + bytes(0x00, 0x02, 0x03, 0x00, 0x00, 0x00) // A number, 3
        assertContentEquals(expected, changed)
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
