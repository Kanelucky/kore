package org.koremc.registry

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ItemDataTest {
    private val pickaxe = ItemStack(ItemDefinition("minecraft:wooden_pickaxe", 300, componentBased = false, ItemVersion.LEGACY, maxDurability = 59))

    /** Rolls that always land on [roll], or the highest they can if it's past that. */
    private fun rolling(roll: Int) = object : Random() {
        override fun nextBits(bitCount: Int) = 0
        override fun nextInt(until: Int) = minOf(roll, until - 1)
    }

    @Test
    fun `a tool wears a use at a time, and lasts one past its durability`() {
        assertEquals(1, pickaxe.worn(1)?.damage)
        assertEquals(59, pickaxe.withDamage(57).worn(2)?.damage) // None left, but it works once more
        assertNull(pickaxe.withDamage(59).worn(1))
    }

    @Test
    fun `Unbreaking spares a use with a chance of its level in one more`() {
        val unbreaking = pickaxe.withEnchantments(listOf(EnchantmentLevel(UNBREAKING, 3)))

        // Of the four rolls at level 3, three spare the use, and only the one left wears it.
        assertSame(unbreaking, unbreaking.worn(1, rolling(1)))
        assertEquals(2, unbreaking.worn(2, rolling(0))?.damage)
    }

    @Test
    fun `what has no durability never wears`() {
        val stick = ItemStack(ItemDefinition("minecraft:stick", 320, componentBased = false, ItemVersion.LEGACY))
        assertSame(stick, stick.worn(2))
    }

    @Test
    fun `experience mends two uses a point, and uses only what it needs`() {
        assertEquals(pickaxe.withDamage(4) to 3, pickaxe.withDamage(10).mendedBy(3))
        // Three uses mended takes two points, the last of them only half used.
        assertEquals(pickaxe to 2, pickaxe.withDamage(3).mendedBy(5))
    }

    @Test
    fun `an enchantment's level is 0 on an item without it`() {
        val efficient = pickaxe.withEnchantments(listOf(EnchantmentLevel(EFFICIENCY, 4)))
        assertEquals(4, efficient.levelOf(EFFICIENCY))
        assertEquals(0, efficient.levelOf(MENDING))
    }
}
