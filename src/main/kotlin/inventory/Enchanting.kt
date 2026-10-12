package org.koremc.inventory

import org.koremc.registry.Enchantment
import org.koremc.registry.EnchantmentLevel
import org.koremc.registry.Enchantments
import org.koremc.registry.ItemStack
import org.koremc.registry.enchantments
import kotlin.math.roundToInt
import kotlin.random.Random

/** The most bookshelves around an enchanting table that count. */
const val MAX_BOOKSHELVES = 15

/** One of the three things an enchanting table offers: the level a player needs for it, and the enchantments it gives. */
data class EnchantingOffer(val cost: Int, val enchantments: List<EnchantmentLevel>)

/**
 * What an enchanting table with [bookshelves] around it offers for
 * [stack], picked by the player's [seed], which changes each time they
 * enchant. Nothing for an item a table can't enchant, or one enchanted
 * already. Otherwise, three offers: the cheapest needing a third of what
 * the dearest does, which more bookshelves raise. Each gives an
 * enchantment the item takes at a level that fits its cost, picked by how
 * common each is, and perhaps more that go with it, likelier the dearer
 * the offer.
 */
fun Enchantments.offersFor(stack: ItemStack, bookshelves: Int, seed: Int): List<EnchantingOffer> {
    val enchantability = stack.item.enchantability
    if (enchantability <= 0 || stack.count != 1 || stack.enchantments.isNotEmpty()) return emptyList()
    val offered = all.filter { it.offeredFor(stack.item) }
    if (offered.isEmpty()) return emptyList()
    val random = Random(seed)
    val shelves = bookshelves.coerceIn(0, MAX_BOOKSHELVES)
    val base = random.nextInt(8) + 1 + (shelves shr 1) + random.nextInt(shelves + 1)
    val costs = listOf(maxOf(base / 3, 1), base * 2 / 3 + 1, maxOf(base, shelves * 2))
    return costs.map { cost -> EnchantingOffer(cost, pick(offered, enchantability, cost, random)) }
}

/** The enchantments an offer costing [level] gives an item of [enchantability], from those [offered] for it. */
private fun pick(offered: List<Enchantment>, enchantability: Int, level: Int, random: Random): List<EnchantmentLevel> {
    var cost = level + 1 + random.nextInt(enchantability / 4 + 1) + random.nextInt(enchantability / 4 + 1)
    val bonus = (random.nextFloat() + random.nextFloat() - 1) * 0.15f
    cost = maxOf(1, (cost + cost * bonus).roundToInt())

    // Each enchantment at the highest level whose cost range takes this cost in.
    var available = offered.mapNotNull { enchantment ->
        (enchantment.maxLevel downTo 1).firstOrNull { cost in enchantment.minCost.at(it)..enchantment.maxCost.at(it) }?.let { enchantment to it }
    }
    if (available.isEmpty()) return emptyList()
    val picked = mutableListOf<Pair<Enchantment, Int>>()
    picked += weighted(available, random)
    available = available - picked.last()
    while (random.nextInt(50) <= cost) {
        val last = picked.last().first
        available = available.filter { (enchantment, _) -> enchantment.compatibleWith(last) && enchantment.id != last.id }
        if (available.isEmpty()) break
        picked += weighted(available, random)
        available = available - picked.last()
        cost /= 2
    }
    return picked.map { (enchantment, level) -> EnchantmentLevel(enchantment.id, level) }
}

/** One of [choices], each as likely as its enchantment's weight. */
private fun weighted(choices: List<Pair<Enchantment, Int>>, random: Random): Pair<Enchantment, Int> {
    var roll = random.nextInt(choices.sumOf { it.first.weight })
    for (choice in choices) {
        roll -= choice.first.weight
        if (roll < 0) return choice
    }
    return choices.last()
}

/** Made-up words an enchanting table shows each offer in, in the runes the client draws. */
private val RUNE_WORDS = listOf(
    "ancient", "ash", "binding", "bright", "cinder", "crest", "dawn", "deep", "drift", "dusk", "ember", "far", "flint", "frost",
    "gale", "gleam", "grove", "hollow", "iron", "kindle", "lantern", "loom", "mire", "moss", "north", "oath", "pale", "quill",
    "rain", "rune", "shard", "sigil", "spark", "stone", "storm", "thorn", "tide", "vale", "veil", "ward", "whisper", "wild",
)

/** A few of the made-up words, picked by [random], for an offer's runes. */
fun runes(random: Random): String = List(random.nextInt(2, 5)) { RUNE_WORDS[random.nextInt(RUNE_WORDS.size)] }.joinToString(" ")
