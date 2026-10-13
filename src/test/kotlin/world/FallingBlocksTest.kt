package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.protocol.LevelEvent
import org.koremc.registry.AIR
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import org.koremc.registry.CreativeInventory
import org.koremc.registry.ItemStacks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FallingBlocksTest {
    private fun state(name: String, vararg properties: Pair<String, Any>) = BlockState(name, NbtMap.fromMap(mapOf(*properties)), version = 18168865)

    private val air = state(AIR)
    private val stone = state("minecraft:stone")
    private val sand = state("minecraft:sand")
    private val gravel = state("minecraft:gravel")
    private val shortGrass = state("minecraft:short_grass")
    private val torch = state("minecraft:torch", TORCH_FACING to "top")
    private val anvils = listOf("anvil", "chipped_anvil", "damaged_anvil").associateWith { state("minecraft:$it", CARDINAL to "south") }
    private val snow = state(SNOW_LAYER, "covered_bit" to 0.toByte(), "height" to 0)
    private val suspicious = (0..1).map { hanging -> state("minecraft:suspicious_sand", "brushed_progress" to 0, HANGING to hanging.toByte()) }
    private val dripstone = listOf("tip", "frustum").associateWith { state("minecraft:pointed_dripstone", "dripstone_thickness" to it, HANGING to 1.toByte()) }
    private val palette = BlockPalette(listOf(air, stone, sand, gravel, shortGrass, torch, snow) + anvils.values + suspicious + dripstone.values)

    // One layer of stone at the bottom of the world, which the ground is the top of.
    private val world = FlatWorld(palette, listOf(stone))
    private val ground = BlockPos(0, MIN_Y + 1, 0)
    private fun up(blocks: Int) = ground.copy(y = ground.y + blocks)

    /** Ticks the world's blocks until nothing's falling or due to, returning what each tick changed. */
    private fun untilSettled(): List<Changes> {
        val ticks = mutableListOf<Changes>()
        while (ticks.size < 200) {
            ticks += world.tickBlocks()
            if (world.fallingBlocks.isEmpty() && !world.hasScheduledUpdates && world.blockChanges.isEmpty()) break
        }
        return ticks
    }

    @Test
    fun `sand with nothing under it starts to fall a moment later, as a falling block, and lands on the ground in its place`() {
        world[up(3)] = sand

        world.tickBlocks()
        world.tickBlocks()
        assertEquals(sand, world.stateAt(up(3)), "not yet")
        val started = world.tickBlocks()
        assertEquals(air, world.stateAt(up(3)))
        val falling = world.fallingBlocks.single()
        assertEquals(sand, falling.state)
        assertEquals(Vec3(0.5f, up(3).y.toFloat(), 0.5f), falling.position)
        assertTrue(up(3) in started.reshaped)

        val ticks = untilSettled()
        assertEquals(sand, world.stateAt(ground))
        assertTrue(ticks.any { ground in it.reshaped }, "players see it land")
        assertEquals(emptyList(), world.fallingBlocks.toList())
    }

    @Test
    fun `taking away what's under a stack of sand and gravel brings it all down, one on top of another`() {
        world[ground] = stone
        world[up(1)] = sand
        world[up(2)] = gravel
        untilSettled()
        assertEquals(listOf(stone, sand, gravel), (0..2).map { world.stateAt(up(it)) }, "it stands on stone")

        world[ground] = air
        untilSettled()

        assertEquals(listOf(sand, gravel, air), (0..2).map { world.stateAt(up(it)) })
    }

    @Test
    fun `a falling block takes the place of grass where it lands, but drops as its item on a torch`() {
        world[ground] = shortGrass
        world[ground.copy(x = 2)] = torch
        world[up(4)] = sand
        world[up(4).copy(x = 2)] = gravel

        val dropped = untilSettled().flatMap { it.dropped }

        assertEquals(sand, world.stateAt(ground))
        assertEquals(torch, world.stateAt(ground.copy(x = 2)))
        assertEquals(listOf(gravel), dropped.map { it.second })
        assertEquals(ground.copy(x = 2), dropped.single().first.toBlockPos())
    }

    @Test
    fun `an anvil clangs as it lands, and wears from falling far enough, until a damaged one breaks`() {
        world[up(1)] = anvils.getValue("anvil")
        var ticks = untilSettled()
        assertEquals(anvils.getValue("anvil"), world.stateAt(ground), "a block's fall never wears it")
        assertEquals(listOf(WorldEvent(ground, LevelEvent.SOUND_ANVIL_LAND, 0)), ticks.flatMap { it.events })

        // Twenty blocks wears it for certain, at five in a hundred a block.
        world[ground] = air
        world[up(20)] = anvils.getValue("anvil")
        untilSettled()
        assertEquals(anvils.getValue("chipped_anvil"), world.stateAt(ground))

        world[ground] = air
        world[up(20)] = anvils.getValue("damaged_anvil")
        ticks = untilSettled()
        assertEquals(air, world.stateAt(ground))
        assertEquals(listOf(WorldEvent(ground, LevelEvent.SOUND_ANVIL_BREAK, 0)), ticks.flatMap { it.events })
        assertEquals(emptyList(), ticks.flatMap { it.dropped })
    }

    @Test
    fun `suspicious sand crumbles where it lands, dropping nothing, unless it hangs from the block above, when it doesn't fall`() {
        world[up(5)] = suspicious[1]
        world[up(3)] = suspicious[0]

        val ticks = untilSettled()

        assertEquals(suspicious[1], world.stateAt(up(5)))
        assertEquals(air, world.stateAt(ground))
        assertEquals(listOf(BlockSound(ground, "break", suspicious[0].networkId)), ticks.flatMap { it.sounds })
        assertEquals(emptyList(), ticks.flatMap { it.dropped })
    }

    @Test
    fun `snow falls when what holds it up goes, as in Bedrock, landing on the ground`() {
        world[ground] = stone
        world[up(1)] = snow
        untilSettled()

        world.breakBlock(ground)
        untilSettled()

        assertEquals(snow, world.stateAt(ground))
        assertEquals(air, world.stateAt(up(1)))
    }

    @Test
    fun `pointed dripstone hanging from a block that goes falls, all of it, and breaks where it lands, with its sound`() {
        world[up(6)] = stone
        world[up(5)] = dripstone.getValue("frustum")
        world[up(4)] = dripstone.getValue("tip")
        untilSettled()

        world.breakBlock(up(6))
        val ticks = untilSettled()

        assertEquals(listOf(air, air, air), listOf(world.stateAt(ground), world.stateAt(up(4)), world.stateAt(up(5))))
        assertEquals(listOf(dripstone.getValue("tip"), dripstone.getValue("frustum")), ticks.flatMap { it.dropped }.map { it.second })
        assertEquals(2, ticks.flatMap { it.sounds }.count { it.sound == "land" })
    }

    @Test
    fun `a block falling as the world's saved comes back falling, and what falls is due to when it's saved`() {
        world.spawnFallingBlock(sand, Vec3(0.5f, -50f, 0.5f), fromY = -40, velocity = Vec3(0f, -0.5f, 0f))

        val saved = world.savedActors().values.flatten().single()
        assertEquals("minecraft:falling_block", saved.getString("identifier"))
        assertEquals(sand.name, saved.getCompound("FallingBlock").getString("name"))

        val loaded = FlatWorld(palette, listOf(stone))
        assertTrue(loaded.restoreActor(saved, ItemStacks(emptyList(), CreativeInventory(emptyList(), emptyList()), palette)))
        val falling = loaded.fallingBlocks.single()
        assertEquals(sand, falling.state)
        assertEquals(Vec3(0.5f, -50f, 0.5f), falling.position)
        assertEquals(-0.5f, falling.velocity.y)

        assertEquals(listOf(BlockUpdate.FALL), sand.dueFor)
        assertFalse(stone.fallsFreely)
    }
}
