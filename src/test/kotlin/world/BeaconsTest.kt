package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.registry.AIR
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import org.koremc.registry.BlockTrait
import org.koremc.registry.BlockTraits

import org.koremc.registry.Tint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BeaconsTest {
    private fun state(name: String) = BlockState(name, NbtMap.EMPTY, version = 1)

    private val stone = state("minecraft:stone")
    private val bedrock = state("minecraft:bedrock")
    private val glass = state("minecraft:glass")
    private val iron = state("minecraft:iron_block")
    private val gold = state("minecraft:gold_block")
    private val beacon = state(BEACON)
    private val palette = BlockPalette(listOf(state(AIR), stone, bedrock, glass, iron, gold, beacon))

    /** Stone and bedrock stop all light, and glass none. */
    private val traits = BlockTraits(
        listOf(stone, bedrock).associate { it.networkId to BlockTrait(0, Tint.NONE, 15) } + (glass.networkId to BlockTrait(0, Tint.NONE, 0)),
    )

    // One layer of stone at the bottom of the world: blocks go from y -63 up.
    private val world = FlatWorld(palette, listOf(stone))

    /** Where the beacon stands, on top of the tallest pyramid it could have. */
    private val at = BlockPos(0, -59, 0)

    /** Builds [layers] of pyramid under the beacon, of iron but for one gold block in each, and puts the beacon on top. */
    private fun pyramid(layers: Int) {
        for (layer in 1..layers) for (x in -layer..layer) for (z in -layer..layer) {
            world[BlockPos(at.x + x, at.y - layer, at.z + z)] = if (x == layer && z == 0) gold else iron
        }
        world[at] = beacon
    }

    @Test
    fun `a beacon's pyramid counts each full layer under it, up to four, and a gap ends it`() {
        world[at] = beacon
        assertEquals(0, world.beaconLevel(at))
        pyramid(4)
        assertEquals(4, world.beaconLevel(at))

        world[BlockPos(at.x + 2, at.y - 2, at.z - 2)] = stone
        assertEquals(1, world.beaconLevel(at))
    }

    @Test
    fun `a block that stops all light blocks a beacon's beam, but not glass, nor bedrock`() {
        pyramid(1)
        world[at.copy(y = at.y + 5)] = glass
        world[at.copy(y = at.y + 9)] = bedrock
        assertFalse(world.beamBlocked(at, traits))

        world[at.copy(y = at.y + 20)] = stone
        assertTrue(world.beamBlocked(at, traits))
    }

    @Test
    fun `a beacon's beam comes on and goes out with a sound, as its pyramid and the sky above it allow`() {
        world[at] = beacon
        assertEquals(emptyList(), world.lookAtBeacons(traits).sounds)
        pyramid(1)
        assertEquals(listOf(BlockSound(at, "beacon.activate", -1)), world.lookAtBeacons(traits).sounds)
        assertEquals(emptyList(), world.lookAtBeacons(traits).sounds)

        world[at.copy(y = at.y + 1)] = stone
        assertEquals(listOf(BlockSound(at, "beacon.deactivate", -1)), world.lookAtBeacons(traits).sounds)
    }

    @Test
    fun `a shining beacon gives its powers, a secondary only on four layers, and the primary again at level two`() {
        pyramid(3)
        assertEquals(emptyList(), world.lookAtBeacons(traits).shining, "without a power chosen")

        world.setBeaconPowers(at, Effect.SPEED, secondary = 0)
        val shining = world.lookAtBeacons(traits).shining.single()
        assertEquals(listOf(EffectLevel(Effect.SPEED)), shining.effects)
        assertEquals(14 * 20, shining.ticks)

        pyramid(4)
        world.setBeaconPowers(at, Effect.SPEED, Effect.SPEED)
        assertEquals(listOf(EffectLevel(Effect.SPEED, amplifier = 1)), world.lookAtBeacons(traits).shining.single().effects)
        world.setBeaconPowers(at, Effect.STRENGTH, Effect.REGENERATION)
        assertEquals(listOf(EffectLevel(Effect.STRENGTH), EffectLevel(Effect.REGENERATION)), world.lookAtBeacons(traits).shining.single().effects)

        // Down to two layers, Strength is beyond it, and so the secondary too.
        world[BlockPos(at.x + 3, at.y - 3, at.z)] = stone
        assertEquals(emptyList(), world.lookAtBeacons(traits).shining)
        world.setBeaconPowers(at, Effect.JUMP_BOOST, Effect.REGENERATION)
        assertEquals(listOf(EffectLevel(Effect.JUMP_BOOST)), world.lookAtBeacons(traits).shining.single().effects)
    }

    @Test
    fun `each layer of pyramid brings its own primary powers, and the fourth a secondary of Regeneration or the primary again`() {
        assertFalse(beaconGives(0, Effect.SPEED, 0))
        assertTrue(beaconGives(1, Effect.HASTE, 0))
        assertFalse(beaconGives(1, Effect.RESISTANCE, 0))
        assertTrue(beaconGives(2, Effect.JUMP_BOOST, 0))
        assertTrue(beaconGives(3, Effect.SPEED, 0))
        assertFalse(beaconGives(3, Effect.STRENGTH, Effect.REGENERATION))
        assertTrue(beaconGives(4, Effect.STRENGTH, Effect.REGENERATION))
        assertTrue(beaconGives(4, Effect.HASTE, Effect.HASTE))
        assertFalse(beaconGives(4, Effect.HASTE, Effect.SPEED))
        assertFalse(beaconGives(4, Effect.REGENERATION, 0))
    }

    @Test
    fun `a beacon's powers reach ten blocks for each layer and ten more, one more to the north and west, and only so far below it`() {
        val shining = Shining(at, level = 1, effects = emptyList())
        fun reaches(x: Int, y: Int = at.y, z: Int = 0) = shining.reaches(Vec3(x + 0.5f, y.toFloat(), z + 0.5f))
        assertTrue(reaches(20))
        assertFalse(reaches(21))
        assertTrue(reaches(-21))
        assertFalse(reaches(-22))
        assertTrue(reaches(0, z = -21))
        assertFalse(reaches(0, z = 21))
        assertTrue(reaches(0, y = 300))
        assertTrue(reaches(0, y = at.y - 20))
        assertFalse(reaches(0, y = at.y - 21))
        assertEquals(10 * 20, shining.ticks)
    }

    @Test
    fun `a stronger effect takes the place of a weaker one, as strong a one lengthens it, and effects wear off`() {
        val effects = Effects()
        assertTrue(effects.give(EffectLevel(Effect.SPEED), 100))
        assertFalse(effects.give(EffectLevel(Effect.SPEED), 50))
        assertTrue(effects.give(EffectLevel(Effect.SPEED), 150))
        assertTrue(effects.give(EffectLevel(Effect.SPEED, amplifier = 1), 10))
        assertFalse(effects.give(EffectLevel(Effect.SPEED), 400))
        assertEquals(2, effects.level(Effect.SPEED))
        assertEquals(10, effects[Effect.SPEED]?.ticksLeft)

        // Speed and Haste show particles to others, by number, seven bits each: the number, then whether it's ambient, as a beacon's are.
        effects.give(EffectLevel(Effect.HASTE), 10)
        assertEquals((((Effect.SPEED shl 1) or 0).toLong() shl 7) or (Effect.HASTE shl 1).toLong(), effects.visible())
        assertTrue(effects.give(EffectLevel(Effect.SPEED, amplifier = 2), 10, ambient = true))
        assertEquals((((Effect.SPEED shl 1) or 1).toLong() shl 7) or (Effect.HASTE shl 1).toLong(), effects.visible())

        repeat(9) { assertEquals(emptyList(), effects.tick()) }
        assertEquals(listOf(Effect.SPEED, Effect.HASTE), effects.tick().sorted())
        assertNull(effects[Effect.SPEED])
        assertEquals(0, effects.level(Effect.SPEED))
        assertEquals(0L, effects.visible())
    }
}
