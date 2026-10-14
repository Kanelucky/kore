package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.registry.AIR
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExperienceTest {
    @Test
    fun `experience fills the bar, and a full bar is a level, each one needing more`() {
        val experience = PlayerExperience()
        assertEquals(7, experienceForLevel(0))
        assertEquals(47, experienceForLevel(17))
        assertEquals(112, experienceForLevel(30))

        assertTrue(experience.add(10))
        assertEquals(1, experience.level)
        assertEquals(3f / 9, experience.progress, 1e-5f)

        // From nothing to level 30 takes 1395 experience.
        val fresh = PlayerExperience()
        fresh.add(1395)
        assertEquals(30, fresh.level)
        assertEquals(0f, fresh.progress, 1e-5f)
    }

    @Test
    fun `levels come and go whole, keeping the bar as it is, and taking more than there are takes everything`() {
        val experience = PlayerExperience(level = 5, progress = 0.5f)
        experience.addLevels(-3)
        assertEquals(2, experience.level)
        assertEquals(0.5f, experience.progress)
        experience.addLevels(-2)
        assertEquals(0, experience.level)
        assertEquals(0.5f, experience.progress)
        experience.addLevels(-1)
        assertEquals(0, experience.level)
        assertEquals(0f, experience.progress)
    }

    @Test
    fun `experience drops as orbs of the game's sizes, which fly to a player nearby and lie still otherwise`() {
        val stone = BlockState("minecraft:stone", NbtMap.EMPTY, 1)
        val world = FlatWorld(BlockPalette(listOf(BlockState(AIR, NbtMap.EMPTY, 1), stone)), listOf(stone))
        world.dropExperience(Vec3(0.5f, -63f, 0.5f), 30)
        assertEquals(listOf(17, 7, 3, 3), world.orbs.map { it.value })

        repeat(40) { world.tickOrbs(emptyList()) }
        assertTrue(world.orbs.all { it.onGround && it.position.y == -63f })

        val player = Vec3(4.5f, -63f, 0.5f)
        val before = world.orbs.map { it.position.x }
        repeat(20) { world.tickOrbs(listOf(player)) }
        assertTrue(world.orbs.map { it.position.x }.zip(before).all { (after, start) -> after > start })
    }

    @Test
    fun `an orb falling to a hair above the ground lands on it, rather than hovering there`() {
        val stone = BlockState("minecraft:stone", NbtMap.EMPTY, 1)
        val world = FlatWorld(BlockPalette(listOf(BlockState(AIR, NbtMap.EMPTY, 1), stone)), listOf(stone))
        // Less than the hair that counts as touching above the ground, as a fall can leave an orb at the end of a tick.
        val orb = world.spawnOrb(1, Vec3(0.5f, -62.99995f, 0.5f), Vec3(0f, 0f, 0f))

        world.tickOrbs(emptyList())

        assertEquals(-63f, orb.position.y)
        assertTrue(orb.onGround)
    }

    @Test
    fun `a fraction of experience is the whole of it, and one more as often as the fraction says`() {
        val rolls = List(1000) { wholeExperience(2.25f) }
        assertTrue(rolls.all { it == 2 || it == 3 })
        assertTrue(rolls.count { it == 3 } in 150..350)
    }
}
