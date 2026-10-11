package org.koremc.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ChatTest {
    @Test
    fun `a chat message names who sent it`() {
        val expected = "09" + "00" + "01" + "01" + "055374657665" + "0568656c6c6f" + // Not translated, an authored body, chat, from Steve
            "0432353335" + "00" + "00" // Steve's XUID, no platform chat ID or filtered version
        assertContentEquals(hex(expected), Text(Text.CHAT, "hello", sourceName = "Steve", xuid = "2535").encode())
        assertEquals(Text(Text.CHAT, "hello", sourceName = "Steve", xuid = "2535"), decodeClientPacket(hex(expected)))
    }

    @Test
    fun `a translated message carries its parameters`() {
        val expected = "09" + "01" + "02" + "02" + "1dc2a765256d756c7469706c617965722e706c617965722e6a6f696e6564" + "01055374657665" + "00" + "00" + "00"
        val joined = Text(Text.TRANSLATION, "§e%multiplayer.player.joined", parameters = listOf("Steve"), needsTranslation = true)
        assertContentEquals(hex(expected), joined.encode())
    }

    @Test
    fun `a filtered version of a message is read past`() {
        val packet = hex("09" + "00" + "01" + "01" + "055374657665" + "0568656c6c6f" + "00" + "00" + "01" + "05682a2a2a6f")
        assertEquals(Text(Text.CHAT, "hello", sourceName = "Steve"), decodeClientPacket(packet))
    }

    private fun hex(digits: String) = ByteArray(digits.length / 2) { digits.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
