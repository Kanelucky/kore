package org.koremc.world

import org.koremc.registry.Mining
import org.koremc.registry.ToolKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MiningTest {
    private val stone = Mining(1.5, ToolKind.PICKAXE, harvestTier = 0)
    private val dirt = Mining(0.5, ToolKind.SHOVEL)
    private val log = Mining(2.0, ToolKind.AXE)
    private val obsidian = Mining(50.0, ToolKind.PICKAXE, harvestTier = 3)

    @Test
    fun `by hand, blocks that need a tool take longer than those that don't`() {
        // The game's own times: dirt in 0.75 seconds, a log in 3, and stone, which needs a pickaxe, in 7.5.
        assertEquals(15, breakTicks(dirt, held = null))
        assertEquals(60, breakTicks(log, held = null))
        assertEquals(150, breakTicks(stone, held = null))
    }

    @Test
    fun `the right tool digs at its tier's speed, and harvests what its tier allows`() {
        assertEquals(23, breakTicks(stone, "minecraft:wooden_pickaxe")) // 1.15 seconds
        assertEquals(188, breakTicks(obsidian, "minecraft:diamond_pickaxe")) // 9.4 seconds
        // An iron pickaxe digs obsidian faster, but can't harvest it, so it's slow all the same.
        assertEquals(834, breakTicks(obsidian, "minecraft:iron_pickaxe"))
        // The wrong kind of tool is as good as a hand.
        assertEquals(150, breakTicks(stone, "minecraft:diamond_shovel"))
    }

    @Test
    fun `some blocks break at once, and some never outside creative`() {
        assertEquals(0, breakTicks(Mining(0.0), held = null))
        assertEquals(0, breakTicks(Mining(0.2, ToolKind.HOE), "minecraft:golden_hoe")) // Leaves before a gold hoe
        assertNull(breakTicks(Mining(-1.0), "minecraft:netherite_pickaxe"))
    }

    @Test
    fun `hardness a hair over its value as a float doesn't round up a tick`() {
        // Concrete's 1.8, as data-driven blocks give it, is 1.7999999523 as a float. By hand, it takes 9 seconds.
        assertEquals(180, breakTicks(Mining(1.8f.toDouble(), ToolKind.PICKAXE, harvestTier = 0), held = null))
        assertEquals(18, breakTicks(Mining(0.6f.toDouble(), ToolKind.SHOVEL), held = null))
    }
}
