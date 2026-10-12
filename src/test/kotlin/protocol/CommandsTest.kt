package org.koremc.protocol

import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import org.koremc.world.Vec3
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CommandsTest {
    private val uuid = UUID(0x0102030405060708, 0x090A0B0C0D0E0F10)

    @Test
    fun `a command request is the line, where it came from, by name, and the command version`() {
        val packet = ByteBufUtil.getBytes(
            Unpooled.buffer().writeVarUInt(77)
                .writeString("/gamemode c")
                .writeString("player").writeUuid(uuid).writeString("").writeLongLE(0)
                .writeBoolean(false).writeString("52"),
        )

        assertEquals(CommandRequest("/gamemode c", CommandOrigin("player", uuid), internal = false, version = "52"), decodeClientPacket(packet))
    }

    @Test
    fun `a command's output repeats its origin, then each message with its parameters`() {
        val output = CommandOutput(CommandOrigin("player", uuid), listOf(CommandMessage("commands.op.success", true, listOf("Steve"))), successCount = 1)

        val expected = Unpooled.buffer().writeVarUInt(79)
            .writeString("player").writeUuid(uuid).writeString("").writeLongLE(0)
            .writeString("alloutput").writeIntLE(1)
            .writeVarUInt(1).writeString("commands.op.success").writeBoolean(true).writeVarUInt(1).writeString("Steve")
            .writeBoolean(false) // No data set
        assertContentEquals(ByteBufUtil.getBytes(expected), output.encode())
    }

    @Test
    fun `available commands list each enum value once, and parameters point at enums by index`() {
        val commands = AvailableCommands(
            enumValues = listOf("creative", "survival"),
            enums = listOf(CommandEnum("GameMode", listOf(0, 1))),
            commands = listOf(
                CommandData(
                    "gamemode", "commands.gamemode.description", CommandPermission.GAME_DIRECTORS, aliases = -1,
                    listOf(listOf(CommandParameter("gameMode", 0x300000, optional = false), CommandParameter("player", 0x100008, optional = true))),
                ),
            ),
        )

        val expected = Unpooled.buffer().writeVarUInt(76)
            .writeVarUInt(2).writeString("creative").writeString("survival")
            .writeVarUInt(0).writeVarUInt(0) // Chained subcommand values, suffixes
            .writeVarUInt(1).writeString("GameMode").writeVarUInt(2).writeIntLE(0).writeIntLE(1)
            .writeVarUInt(0) // Chained subcommands
            .writeVarUInt(1).writeString("gamemode").writeString("commands.gamemode.description").writeShortLE(0).writeString("gamedirectors")
            .writeIntLE(-1).writeVarUInt(0)
            .writeVarUInt(1).writeBoolean(false).writeVarUInt(2)
            .writeString("gameMode").writeIntLE(0x300000).writeBoolean(false).writeByte(0)
            .writeString("player").writeIntLE(0x100008).writeBoolean(true).writeByte(0)
            .writeVarUInt(0).writeVarUInt(0) // Soft enums, constraints
        assertContentEquals(ByteBufUtil.getBytes(expected), commands.encode())
    }

    @Test
    fun `soft enums come last, their words written out`() {
        val commands = AvailableCommands(emptyList(), emptyList(), emptyList(), listOf(SoftEnum("Item", listOf("stone", "dirt"))))

        val expected = Unpooled.buffer().writeVarUInt(76)
            .writeVarUInt(0).writeVarUInt(0).writeVarUInt(0).writeVarUInt(0).writeVarUInt(0).writeVarUInt(0)
            .writeVarUInt(1).writeString("Item").writeVarUInt(2).writeString("stone").writeString("dirt")
            .writeVarUInt(0)
        assertContentEquals(ByteBufUtil.getBytes(expected), commands.encode())
    }

    @Test
    fun `game modes change by their number, and another player's by their ID too`() {
        assertContentEquals(byteArrayOf(62, 2), SetPlayerGameType(GameMode.CREATIVE).encode())
        assertContentEquals(byteArrayOf(0x97.toByte(), 0x01, 12, 14, 0), UpdatePlayerGameType(GameMode.SPECTATOR, 7).encode())
    }

    @Test
    fun `a teleport says it was one, and that a command did it`() {
        val move = MovePlayer(7, Vec3(1f, 2f, 3f), 0f, 0f, 0f, onGround = false, teleport = true).encode()

        val expected = Unpooled.buffer().writeVarUInt(19).writeVarULong(7)
            .writeFloatLE(1f).writeFloatLE(2f).writeFloatLE(3f).writeZero(12)
            .writeByte(2).writeBoolean(false).writeVarULong(0)
            .writeBoolean(true).writeIntLE(3).writeIntLE(0) // A teleport by a command, by no entity
            .writeVarULong(0)
        assertContentEquals(ByteBufUtil.getBytes(expected), move)
    }
}
