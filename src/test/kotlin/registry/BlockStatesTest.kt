package org.koremc.registry

import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType
import org.cloudburstmc.nbt.NbtUtils
import kotlin.io.path.inputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Expected network IDs are the ones the game gave these states, from Cloudburst's 1.26.50 palette.
class BlockStatesTest {
    @Test
    fun `a block without properties hashes its name and an empty compound`() {
        assertEquals(-604749536, blockStateHash("minecraft:air", emptyMap()))
    }

    @Test
    fun `properties hash sorted by key, whatever order they come in`() {
        val stairs = 882976191
        val properties = listOf<Pair<String, Any>>("weirdo_direction" to 2, "upside_down_bit" to 1.toByte(), "minecraft:corner" to "outer_left")

        assertEquals(stairs, blockStateHash("minecraft:stone_stairs", linkedMapOf(*properties.toTypedArray())))
        assertEquals(stairs, blockStateHash("minecraft:stone_stairs", linkedMapOf(*properties.reversed().toTypedArray())))
    }

    @Test
    fun `a property's NBT type is part of the state`() {
        // Without the map's type, Kotlin would make the 3 a byte too, to match the 0.
        val litAsByte = blockStateHash("minecraft:blue_candle", mapOf<String, Any>("candles" to 3, "lit" to 0.toByte()))

        assertEquals(-445722970, litAsByte)
        assertNotEquals(litAsByte, blockStateHash("minecraft:blue_candle", mapOf<String, Any>("candles" to 3, "lit" to 0)))
    }

    @Test
    fun `the unknown block is always -2`() {
        assertEquals(-2, blockStateHash(UNKNOWN_BLOCK, emptyMap()))
    }

    @Test
    fun `every state in the game's palette hashes to the network ID the game gave it`() {
        assumeGameData()

        // Kore's loader ignores the palette's network IDs and hashes instead, so they make an independent answer key.
        val palette = NbtUtils.createGZIPReader(gameData.resolve("block_palette.nbt").inputStream()).use { it.readTag() as NbtMap }
        val blocks = palette.getList("blocks", NbtType.COMPOUND)

        val mismatched = blocks.filter { blockStateHash(it.getString("name"), it.getCompound("states")) != it.getInt("network_id") }

        assertTrue(blocks.isNotEmpty())
        assertEquals(emptyList(), mismatched.map { "${it.getString("name")} ${it.getCompound("states")}" })
    }

    @Test
    fun `the palette finds states by network ID`() {
        val candle = BlockState("minecraft:blue_candle", states("candles" to 3, "lit" to 0.toByte()), version = 18168865)
        val palette = BlockPalette(listOf(BlockState("minecraft:air", NbtMap.EMPTY, version = 18168865), candle))

        assertEquals(candle, palette[-445722970])
        assertNull(palette[0])
    }

    @Test
    fun `states are looked up by name and properties, and must exist`() {
        val lit = BlockState("minecraft:blue_candle", states("candles" to 3, "lit" to 1.toByte()), version = 18168865)
        val palette = BlockPalette(listOf(lit))

        assertEquals(lit, palette.stateOf("minecraft:blue_candle", mapOf<String, Any>("lit" to 1.toByte(), "candles" to 3)))
        assertFailsWith<IllegalArgumentException> { palette.stateOf("minecraft:blue_candle", mapOf<String, Any>("candles" to 3, "lit" to 0.toByte())) }
        assertFailsWith<IllegalArgumentException> { palette.stateOf("minecraft:blue_candle") }
    }

    @Test
    fun `a palette can't hold two states with one network ID`() {
        val air = BlockState("minecraft:air", NbtMap.EMPTY, version = 18168865)

        assertFailsWith<IllegalArgumentException> { BlockPalette(listOf(air, air.copy(version = 1))) }
    }

    @Test
    fun `palettes are read from a list of blocks`() {
        val candle = states("candles" to 3, "lit" to 0.toByte())
        val entry = NbtMap.builder().putString("name", "minecraft:blue_candle").putCompound("states", candle).putInt("version", 18168865).build()

        val palette = readBlockPalette(NbtMap.builder().putList("blocks", NbtType.COMPOUND, entry).build())

        assertEquals(listOf(BlockState("minecraft:blue_candle", candle, 18168865)), palette.states)
        assertFailsWith<IllegalStateException> { readBlockPalette(NbtMap.EMPTY) }
        assertFailsWith<IllegalStateException> { readBlockPalette(NbtMap.builder().putList("blocks", NbtType.COMPOUND, NbtMap.EMPTY).build()) }
    }

    private fun states(vararg properties: Pair<String, Any>) = NbtMap.fromMap(linkedMapOf(*properties))
}
