package org.koremc.protocol

import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import org.koremc.world.BlockPos
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class MapsTest {
    @Test
    fun `a map's data names it, says where it is and how big, then its markers, each tracking an entity of its own, and a square of its pixels, each red first`() {
        val packet = ClientboundMapItemData(
            mapId = 5, centre = BlockPos(0, 0, -128), scale = 1, locked = true,
            markers = listOf(MapMarker(MapMarker.PLAYER, rotation = 4, x = 21, y = -39)),
            patch = MapPatch(x = 2, y = 3, width = 1, height = 2, pixels = intArrayOf(0xFF91BD59.toInt(), 0)),
        )
        val expected = "43" + "0a" + "00" + "01" + "0000ff01" + // Map 5, in the overworld, locked, around 0, 0, -128
            "01010a" + "0101" + // Part of only itself, at scale 1
            "01" + "01" + "00000000" + "01" + "00" + "00" + // Tracking entity 0
            "01" + "01" + "00" + "04" + "15" + "d9" + "00" + "ffffffff" + // One white pointer for it, facing west, at 21, -39, with no label
            "0102" + "0104" + "0104" + "0106" + // One wide and two high, from 2, 3
            "0102" + "91bd59ff" + "00000000"
        assertContentEquals(hex(expected), packet.encode())
    }

    @Test
    fun `a map's data can leave out its markers and its pixels`() {
        assertContentEquals(hex("43" + "0a" + "0000" + "000000" + "01010a" + "0100" + "00" + "00" + "0000000000"), ClientboundMapItemData(5, BlockPos(0, 0, 0), 0, false).encode())
    }

    @Test
    fun `a client asks about a map by its number, with the pixels it has, which are read past`() {
        val packet = Unpooled.buffer().writeVarUInt(68).writeVarLong(-3).writeIntLE(1).writeIntLE(0x11223344).writeShortLE(7)
        assertEquals(MapInfoRequest(-3), decodeClientPacket(ByteBufUtil.getBytes(packet)))
    }

    @Test
    fun `a client picks the number of a map's locked copy`() {
        val packet = Unpooled.buffer().writeVarUInt(131).writeVarLong(-3).writeVarLong(1971533702)
        assertEquals(MapCreateLockedCopy(-3, 1971533702), decodeClientPacket(ByteBufUtil.getBytes(packet)))
    }

    @Test
    fun `an effect starts on an entity, with its strength and how long it lasts`() {
        assertContentEquals(hex("1c" + "05" + "01" + "06" + "02" + "01" + "9003" + "00" + "01"), MobEffect(5, MobEffect.ADD, 3, amplifier = 1, duration = 200, ambient = true).encode())
    }

    private fun hex(digits: String) = ByteArray(digits.length / 2) { digits.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
