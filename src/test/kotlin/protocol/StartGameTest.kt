package org.koremc.protocol

import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class StartGameTest {
    /**
     * Every byte of this was decoded field by field with minecraft-data's
     * definitions for 1.26.51 (protocol 2193), an implementation that shares
     * nothing with Kore's. All 193 bytes were consumed and every field read
     * back as the value below. If this test fails, the layout has changed.
     */
    private val verified = HexFormat.of().parseHex(
        "0b542a020000003f713d83420000003f000020410000b442393000000000000000000000040000060580010e01000000" +
            "000000000000000000000000000100080801000000000000000000010400000000000000000000000000012a00000000" +
            "000000000100000000000000000a4b6f726520576f726c6400005000701700000000000000000e636f7272656c617469" +
            "6f6e2d69640107312e32362e35300a000000000000000000000000000000000000000000000000000000010000000000" +
            "00",
    )

    @Test
    fun `start game encodes exactly as verified`() {
        val packet = StartGame(
            entityId = 42,
            runtimeId = 42,
            playerGameMode = GameMode.CREATIVE,
            position = Vec3(0.5f, 65.62f, 0.5f),
            pitch = 10f,
            yaw = 90f,
            seed = 12345,
            worldGameMode = GameMode.SURVIVAL,
            difficulty = Difficulty.HARD,
            worldSpawn = BlockPos(-3, 64, 7),
            worldName = "Kore World",
            serverVersion = "1.26.50",
            time = 6000,
            multiplayerCorrelationId = "correlation-id",
        )

        assertEquals(HexFormat.of().formatHex(verified), HexFormat.of().formatHex(packet.encode()))
    }
}
