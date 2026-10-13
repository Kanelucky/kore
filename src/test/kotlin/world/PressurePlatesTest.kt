package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.registry.AIR
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import org.koremc.registry.ItemDefinition
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PressurePlatesTest {
    private fun state(name: String, vararg properties: Pair<String, Any>) = BlockState(name, NbtMap.fromMap(mapOf(*properties)), version = 1)

    private val stone = state("minecraft:stone")
    private val lamp = state(REDSTONE_LAMP)
    private val litLamp = state(LIT_REDSTONE_LAMP)
    private val levers = (0..1).map { on -> state("minecraft:lever", "lever_direction" to "up_east_west", "open_bit" to on.toByte()) }

    /** A pressure plate called [name], giving [signal]. */
    private fun plate(name: String, signal: Int = 0) = state("minecraft:$name", REDSTONE_SIGNAL to signal)
    private val plates = listOf("wooden_pressure_plate", "stone_pressure_plate", "light_weighted_pressure_plate", "heavy_weighted_pressure_plate")
        .flatMap { name -> (0..MAX_POWER).map { plate(name, it) } }

    private val palette = BlockPalette(listOf(state(AIR), stone, lamp, litLamp) + levers + plates)

    // One layer of stone at the bottom of the world: blocks go from y -63 up.
    private val world = FlatWorld(palette, listOf(stone))
    private val cobblestone = ItemDefinition("minecraft:cobblestone", 1, componentBased = false, ItemVersion.NONE)

    private val sounds = mutableListOf<String>()

    /** Ticks the world as the server does, with players standing at [players], [times] over. */
    private fun tick(times: Int = 1, players: List<Vec3> = emptyList()) = repeat(times) {
        sounds += world.pressPlates(players).sounds.map { it.sound }
        sounds += world.tickBlocks().sounds.map { it.sound }
        world.updatePower()
    }

    /** Drops [count] separate items at [at], each a stack of its own. */
    private fun drop(at: Vec3, count: Int = 1) = (1..count).map { world.spawnItem(ItemStack(cobblestone), at, Vec3(0f, 0f, 0f), 0) }

    @Test
    fun `a redstone lamp lights as soon as power reaches it, and goes out six ticks after it goes, as in Bedrock`() {
        val at = BlockPos(0, -63, 0)
        val lever = BlockPos(1, -63, 0)
        world[at] = lamp
        world[lever] = levers[1]
        world.updatePower()
        assertEquals(litLamp, world.stateAt(at))

        world[lever] = levers[0]
        world.updatePower()
        tick(5)
        assertEquals(litLamp, world.stateAt(at))
        tick()
        assertEquals(lamp, world.stateAt(at))

        // Power back before it goes out keeps it lit.
        world[lever] = levers[1]
        world.updatePower()
        world[lever] = levers[0]
        world.updatePower()
        tick(3)
        world[lever] = levers[1]
        world.updatePower()
        tick(10)
        assertEquals(litLamp, world.stateAt(at))
    }

    @Test
    fun `a wooden pressure plate presses under an item, powering what's beside it and through the block it's on, and comes back up once it's gone`() {
        val ground = BlockPos(0, -62, 0)
        val at = BlockPos(0, -61, 0)
        val under = BlockPos(0, -63, 0)
        val beside = BlockPos(1, -61, 0)
        world[ground] = stone
        world[at] = plate("wooden_pressure_plate")
        world[under] = lamp
        world[beside] = lamp
        val item = drop(Vec3(0.5f, -61f, 0.5f)).single()

        tick()
        assertEquals(plate("wooden_pressure_plate", 1), world.stateAt(at))
        assertEquals(listOf("pressure_plate.click_on"), sounds)
        assertEquals(listOf(litLamp, litLamp), listOf(world.stateAt(under), world.stateAt(beside)))

        world.removeItem(item.id)
        sounds.clear()
        tick(19)
        assertEquals(plate("wooden_pressure_plate", 1), world.stateAt(at)) // It looks again a second after it was pressed
        tick()
        assertEquals(plate("wooden_pressure_plate"), world.stateAt(at))
        assertEquals(listOf("pressure_plate.click_off"), sounds)
        tick(6)
        assertEquals(listOf(lamp, lamp), listOf(world.stateAt(under), world.stateAt(beside)))
    }

    @Test
    fun `a stone pressure plate presses under a player, but not an item`() {
        val at = BlockPos(0, -63, 0)
        world[at] = plate("stone_pressure_plate")
        drop(Vec3(0.5f, -63f, 0.5f))
        tick()
        assertEquals(plate("stone_pressure_plate"), world.stateAt(at))

        tick(players = listOf(Vec3(0.5f, -63f, 0.5f)))
        assertEquals(plate("stone_pressure_plate", 1), world.stateAt(at))
        // A player beside it, or well above it, doesn't press it.
        world[at] = plate("stone_pressure_plate")
        tick(players = listOf(Vec3(1.5f, -63f, 0.5f), Vec3(0.5f, -62.5f, 0.5f)))
        assertEquals(plate("stone_pressure_plate"), world.stateAt(at))
    }

    @Test
    fun `weighted pressure plates give power by how many things are on them, a step for each, or for each ten`() {
        val light = BlockPos(0, -63, 0)
        val heavy = BlockPos(2, -63, 0)
        world[light] = plate("light_weighted_pressure_plate")
        world[heavy] = plate("heavy_weighted_pressure_plate")
        drop(Vec3(0.5f, -63f, 0.5f), count = 3)
        drop(Vec3(2.5f, -63f, 0.5f), count = 11)
        tick()
        assertEquals(3, world.stateAt(light)?.platePower)
        assertEquals(2, world.stateAt(heavy)?.platePower)

        // A weighted plate looks again sooner, every half a second.
        drop(Vec3(0.5f, -63f, 0.5f), count = 2)
        tick(10)
        assertEquals(5, world.stateAt(light)?.platePower)
        assertTrue(sounds.none { it == "pressure_plate.click_off" })
    }
}
