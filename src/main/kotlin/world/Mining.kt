package org.koremc.world

import org.koremc.registry.Mining
import org.koremc.registry.ToolKind
import kotlin.math.ceil

/** Tool tiers, as their items' names start, with how fast each digs and the tier of block it harvests. */
private enum class Tier(val speed: Double, val level: Int) {
    WOODEN(2.0, 0),
    GOLDEN(12.0, 0),
    STONE(4.0, 1),
    COPPER(5.0, 1),
    IRON(6.0, 2),
    DIAMOND(8.0, 3),
    NETHERITE(9.0, 4),
}

/**
 * How many ticks a player holding the item called [held], or nothing,
 * takes to break a block that takes to [mining] as it does: 0 for a block
 * that breaks at once, and null for one only creative players break.
 *
 * It's the game's own reckoning. A tool of the kind that suits the block
 * digs at its tier's speed, and anything else at 1. Each tick does that
 * speed over the block's hardness: a thirtieth of it when what the player
 * holds harvests the block, and a hundredth when it doesn't. The block
 * breaks once the ticks add up to a whole. Swords and shears dig what
 * suits them at one speed each, whatever they're made of.
 *
 * Kore doesn't count what slows a player down, like digging off the
 * ground or under water, nor what speeds them up, like Efficiency or Haste,
 * which nothing gives them yet.
 */
fun breakTicks(mining: Mining, held: String?): Int? {
    if (mining.unbreakable) return null
    val (kind, tier) = toolOf(held)
    val suited = kind != null && kind == mining.tool
    val speed = when {
        !suited -> 1.0
        kind == ToolKind.SWORD -> 15.0
        kind == ToolKind.SHEARS -> 5.0
        else -> tier?.speed ?: 1.0
    }
    val harvests = mining.harvestTier.let { needed -> needed == null || (suited && (tier?.level ?: 0) >= needed) }
    val ticks = mining.hardness * (if (harvests) 30 else 100) / speed
    // Some hardness comes as a float, a hair over its true value, which mustn't round up to another tick.
    return if (ticks < 1) 0 else ceil(ticks - 1e-4).toInt()
}

/** The kind and tier of tool the item called [item] is, as its name has them, like `minecraft:stone_pickaxe`. */
private fun toolOf(item: String?): Pair<ToolKind?, Tier?> {
    val name = item?.removePrefix("minecraft:") ?: return null to null
    if (name == "shears") return ToolKind.SHEARS to null
    val kind = ToolKind.entries.find { name.endsWith("_" + it.name.lowercase()) } ?: return null to null
    return kind to Tier.entries.find { name.startsWith(it.name.lowercase() + "_") }
}
