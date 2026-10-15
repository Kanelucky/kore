package org.koremc.world

import org.koremc.inventory.SlotStack
import org.koremc.registry.AIR
import org.koremc.registry.BlockState
import org.koremc.registry.Fuel
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemStacks
import org.koremc.registry.VanillaData
import org.koremc.registry.assumeGameData
import org.koremc.registry.vanillaData
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Note blocks and jukeboxes, by the game's own blocks. */
class MusicTest {
    private val blocks get() = data.blocks
    private lateinit var world: FlatWorld
    private lateinit var items: ItemStacks

    // A world of one layer of stone: its top is the ground, and the space above it is free.
    private val ground = BlockPos(0, MIN_Y, 0)
    private val above = ground.side(Face.UP)

    @BeforeTest
    fun startWorld() {
        assumeGameData()
        world = FlatWorld(blocks, listOf(block("minecraft:stone")))
        items = ItemStacks(data.items, data.creative, blocks)
    }

    private fun block(name: String): BlockState = blocks.defaultOf(name) ?: error("no block $name")

    private fun item(name: String): ItemStack = items.of(name) ?: error("no item $name")

    private fun place(name: String, at: BlockPos = above) = world.place(block(name), at.side(Face.DOWN), Face.UP, 0f, Placer(Face.NORTH))

    @Test
    fun `a note block plays the instrument of the block it stands on`() {
        val instruments = mapOf(
            "minecraft:dirt" to 0, "minecraft:stone" to 1, "minecraft:cobblestone" to 1, "minecraft:diamond_ore" to 1, "minecraft:sand" to 2,
            "minecraft:glass" to 3, "minecraft:red_stained_glass" to 3, "minecraft:oak_planks" to 4, "minecraft:stripped_spruce_log" to 4,
            "minecraft:crimson_stem" to 4, "minecraft:chest" to 4, "minecraft:clay" to 5, "minecraft:gold_block" to 6,
            "minecraft:white_wool" to 7, "minecraft:packed_ice" to 8, "minecraft:bone_block" to 9, "minecraft:iron_block" to 10,
            "minecraft:soul_sand" to 11, "minecraft:pumpkin" to 12, "minecraft:emerald_block" to 13, "minecraft:hay_block" to 14,
            "minecraft:glowstone" to 15, "minecraft:redstone_lamp" to 0, "minecraft:oak_leaves" to 0, "minecraft:white_concrete_powder" to 2,
        )
        for ((below, instrument) in instruments) assertEquals(instrument, instrumentOn(block(below)), below)
    }

    @Test
    fun `a note block on copper plays the trumpet, the more worn the more the copper's weathered, waxed or not`() {
        val trumpets = mapOf(
            "minecraft:copper_block" to 16, "minecraft:waxed_copper" to 16, "minecraft:cut_copper" to 16, "minecraft:chiseled_copper" to 16,
            "minecraft:cut_copper_slab" to 16, "minecraft:waxed_cut_copper_stairs" to 16, "minecraft:double_cut_copper_slab" to 16,
            "minecraft:exposed_copper" to 17, "minecraft:waxed_exposed_chiseled_copper" to 17, "minecraft:weathered_cut_copper_slab" to 18,
            "minecraft:waxed_weathered_copper" to 18, "minecraft:oxidized_copper" to 19, "minecraft:waxed_oxidized_double_cut_copper_slab" to 19,
            // Not all copper plays the trumpet.
            "minecraft:raw_copper_block" to 1, "minecraft:copper_ore" to 1, "minecraft:copper_grate" to 0, "minecraft:exposed_copper_bulb" to 0,
        )
        for ((below, instrument) in trumpets) assertEquals(instrument, instrumentOn(block(below)), below)
    }

    @Test
    fun `a mob's head on top of a note block plays the mob's sound, whatever it's on, showing no note, and a player's head keeps it quiet`() {
        world[ground] = block("minecraft:gold_block")
        place("minecraft:noteblock")
        val heads = mapOf(
            "minecraft:zombie_head" to 20, "minecraft:skeleton_skull" to 21, "minecraft:creeper_head" to 22, "minecraft:dragon_head" to 23,
            "minecraft:wither_skeleton_skull" to 24, "minecraft:piglin_head" to 25,
        )
        world.tuneNote(above) // Tuned a note up, the mob's sound plays at its own pitch all the same, the middle note.
        for ((head, instrument) in heads) {
            world[above.side(Face.UP)] = block(head)
            assertEquals(NotePlayed(above, instrument, 12), world.playNote(above), head)
            assertFalse(world.playNote(above)!!.tuned)
        }
        world[above.side(Face.UP)] = block("minecraft:player_head")
        assertNull(world.playNote(above))
        world[above.side(Face.UP)] = block(AIR)
        assertTrue(world.playNote(above)!!.tuned)
    }

    @Test
    fun `a click tunes a note block a note up, round after two octaves, and it plays only with air above it`() {
        world[ground] = block("minecraft:gold_block")
        place("minecraft:noteblock")
        assertEquals("Music", world.blockEntityAt(above)?.getString("id"))

        assertEquals(NotePlayed(above, 6, 1), world.tuneNote(above))
        repeat(23) { world.tuneNote(above) }
        assertEquals(NotePlayed(above, 6, 24), world.playNote(above))
        assertEquals(NotePlayed(above, 6, 0), world.tuneNote(above))

        world[above.side(Face.UP)] = block("minecraft:stone")
        assertNull(world.tuneNote(above)) // Tuned all the same
        world[above.side(Face.UP)] = block(AIR)
        assertEquals(NotePlayed(above, 6, 1), world.playNote(above))
    }

    @Test
    fun `power reaching a note block plays it once, and again only once the power's gone and back`() {
        place("minecraft:noteblock")
        val lever = above.side(Face.EAST)
        world[lever] = blocks.stateOf("minecraft:lever", mapOf("lever_direction" to "up_east_west", "open_bit" to 0.toByte()))
        world.updatePower()

        world.use(lever, Face.NORTH)
        assertEquals(listOf(NotePlayed(above, 1, 0)), world.updatePower().notes)
        assertEquals(emptyList(), world.updatePower().notes)
        world.use(lever, Face.NORTH)
        assertEquals(emptyList(), world.updatePower().notes)
        world.use(lever, Face.NORTH)
        assertEquals(1, world.updatePower().notes.size)
    }

    @Test
    fun `a disc plays in a jukebox, giving power and a comparator its own reading until it's popped out`() {
        place("minecraft:jukebox")
        val played = world.useJukebox(above, item("minecraft:music_disc_cat"), items)
        assertEquals(HeldAfter.USED_UP, played?.held)
        val started = played?.songs?.single()
        assertEquals("record.cat", started?.song)
        assertEquals("minecraft:music_disc_cat", world.discIn(above, items)?.item?.name)
        assertEquals("%item.record_cat.desc", world.songTitleIn(above))
        assertEquals(2, world.fullness(above))
        assertTrue(world.isPowered(above.side(Face.EAST)))
        assertNull(world.useJukebox(BlockPos(5, above.y, 5), item("minecraft:stone"), items)) // Not a disc, nor a jukebox with one

        val popped = world.useJukebox(above, item("minecraft:stone"), items)
        assertEquals(listOf("minecraft:music_disc_cat"), popped?.dropped?.map { it.item.name })
        assertEquals(above.side(Face.UP), popped?.dropAt)
        assertEquals(listOf(SongChange(above, started!!.handle)), popped?.songs) // Stopped by the handle it started with
        assertNull(world.discIn(above, items))
        assertEquals(0, world.fullness(above))
        assertFalse(world.isPowered(above.side(Face.EAST)))
    }

    @Test
    fun `a hopper pointing into an empty jukebox puts a disc in, which plays`() {
        place("minecraft:jukebox")
        val hopper = above.side(Face.WEST)
        world[hopper] = blocks.stateOf("minecraft:hopper", mapOf("facing_direction" to Face.EAST.ordinal, "toggle_bit" to 0.toByte()))
        world.hopperAt(hopper)!!.slots[0] = SlotStack(item("minecraft:stone"), world.newStackId())
        world.hopperAt(hopper)!!.slots[1] = SlotStack(item("minecraft:music_disc_far").copy(count = 2), world.newStackId())
        val fuel = Fuel(data.recipes, items.of("minecraft:bucket"))

        val songs = mutableListOf<SongChange>()
        repeat(HOPPER_COOLDOWN * 2) { world.tickMachines(data.recipes, fuel, songs = songs) }
        assertEquals("minecraft:music_disc_far", world.discIn(above, items)?.item?.name)
        assertTrue(world.playingSong(above))
        assertEquals(listOf(above to "record.far"), songs.map { it.pos to it.song })
        // The other disc waits, as does the stone, which a jukebox doesn't take.
        assertEquals(listOf(1, 1), world.hopperAt(hopper)!!.slots.contents().filterNotNull().map { it.stack.count })
    }

    @Test
    fun `a jukebox stops giving power when its song ends, but keeps its disc`() {
        place("minecraft:jukebox")
        world.useJukebox(above, item("minecraft:music_disc_11"), items)
        // 11 lasts a minute and eleven seconds.
        repeat(71 * 20 - 1) { world.tickBlocks() }
        assertTrue(world.playingSong(above))
        world.tickBlocks()
        assertFalse(world.playingSong(above))
        assertEquals(11, world.fullness(above))
    }

    @Test
    fun `breaking a jukebox stops its song and drops its disc`() {
        place("minecraft:jukebox")
        val handle = world.useJukebox(above, item("minecraft:music_disc_pigstep"), items)!!.songs.single().handle
        val changes = world.breakBlock(above)
        val drops = Drops(data.items, data.creative, blocks)
        val broken = changes.broken.getValue(above)
        assertEquals(listOf(SongChange(above, handle)), changes.songs)
        assertFalse(world.playingSong(above))
        assertEquals(listOf("minecraft:jukebox", "minecraft:music_disc_pigstep"), drops.of(broken.state, broken.blockEntity).map { it.item.name })
    }

    private companion object {
        val data: VanillaData get() = vanillaData
    }
}
