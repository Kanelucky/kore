package org.koremc.inventory

import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType
import org.koremc.registry.BOOK
import org.koremc.registry.ENCHANTED_BOOK
import org.koremc.registry.EnchantmentLevel
import org.koremc.registry.Enchantments
import org.koremc.registry.ItemStack
import org.koremc.registry.customName
import org.koremc.registry.damage
import org.koremc.registry.durability
import org.koremc.registry.enchantments
import org.koremc.registry.repairCost
import org.koremc.registry.withCustomName
import org.koremc.registry.withDamage
import org.koremc.registry.withEnchantments
import org.koremc.registry.withRepairCost
import kotlin.random.Random

/** The materials armour trims come in, by the item that makes each. */
private val TRIM_MATERIALS = mapOf(
    "amethyst_shard" to "amethyst", "copper_ingot" to "copper", "diamond" to "diamond", "emerald" to "emerald", "gold_ingot" to "gold",
    "iron_ingot" to "iron", "lapis_lazuli" to "lapis", "netherite_ingot" to "netherite", "quartz" to "quartz", "redstone" to "redstone",
    "resin_brick" to "resin",
).mapKeys { "minecraft:${it.key}" }

private const val TRIM_TEMPLATE = "_armor_trim_smithing_template"

/**
 * [armour] trimmed in the pattern [template] makes, in the colour of
 * [material], as a smithing table does: its NBT names both. Null if the
 * template isn't a trim's, the material isn't one a trim takes, or the
 * armour has that trim already.
 */
fun trim(armour: ItemStack, template: ItemStack, material: ItemStack): ItemStack? {
    val name = template.item.name.substringAfter(':')
    if (!name.endsWith(TRIM_TEMPLATE)) return null
    val trim = NbtMap.builder().putString("Material", TRIM_MATERIALS[material.item.name] ?: return null).putString("Pattern", name.removeSuffix(TRIM_TEMPLATE)).build()
    if (armour.nbt.getCompound(TRIM, null) == trim) return null
    return armour.copy(count = 1, nbt = armour.nbt.toBuilder().putCompound(TRIM, trim).build())
}

private const val TRIM = "Trim"

/** The colours dyes give banner patterns, by the numbers banners keep them under. */
private val DYE_COLOURS = listOf(
    "black", "red", "green", "brown", "blue", "purple", "cyan", "light_gray", "gray", "pink", "lime", "yellow", "light_blue", "magenta", "orange", "white",
).withIndex().associate { (colour, name) -> "minecraft:${name}_dye" to colour }

/** The patterns a loom weaves only with a banner pattern beside it, by the pattern each item makes. */
private val PATTERN_ITEMS = mapOf(
    "cre" to "creeper", "sku" to "skull", "flo" to "flower", "moj" to "mojang", "bri" to "field_masoned", "cbo" to "bordure_indented",
    "pig" to "piglin", "glb" to "globe", "flw" to "flow", "gus" to "guster",
).mapValues { "minecraft:${it.value}_banner_pattern" }

/** The patterns a loom weaves with dye alone, by the codes banners keep them under. */
private val PLAIN_PATTERNS = setOf(
    "bs", "ts", "ls", "rs", "cs", "ms", "drs", "dls", "ss", "cr", "sc", "ld", "rud", "lud", "rd", "vh", "vhr", "hh", "hhb",
    "bl", "br", "tl", "tr", "bt", "tt", "bts", "tts", "mc", "mr", "bo", "gra", "gru",
)

/** The most patterns a loom weaves into one banner. */
private const val MAX_PATTERNS = 6

private const val BANNER = "minecraft:banner"
private const val PATTERNS = "Patterns"

/**
 * [times] of [banner] with the pattern called [pattern] woven into it in
 * the colour of [dye], as a loom does, or null if it can't: the banner
 * mustn't be ominous, or have six patterns already, and the patterns from
 * banner patterns need theirs beside it, as [patternItem]. The banner
 * pattern isn't used up.
 */
fun weave(banner: ItemStack, dye: ItemStack, patternItem: ItemStack?, pattern: String, times: Int): ItemStack? {
    if (banner.item.name != BANNER || banner.nbt.getInt("Type", 0) == OMINOUS) return null
    val colour = DYE_COLOURS[dye.item.name] ?: return null
    val needs = PATTERN_ITEMS[pattern]
    if (needs == null && pattern !in PLAIN_PATTERNS) return null
    if (needs != null && patternItem?.item?.name != needs) return null
    val patterns = banner.nbt.getList(PATTERNS, NbtType.COMPOUND, emptyList())
    if (patterns.size >= MAX_PATTERNS) return null
    val woven = patterns + NbtMap.builder().putString("Pattern", pattern).putInt("Color", colour).build()
    return banner.copy(count = times, nbt = banner.nbt.toBuilder().putList(PATTERNS, NbtType.COMPOUND, woven).build())
}

/** A banner\x27s type for an ominous banner, which looms leave alone. */
private const val OMINOUS = 1

/** What a grindstone makes, and the experience it gives back for the enchantments it took. */
data class Ground(val output: ItemStack, val experience: Int)

/**
 * What a grindstone makes of [first] and [second], either of which may be
 * missing. Two of the same item that wears out become one, with what both
 * had left and a twentieth more. Either way, every enchantment but curses
 * comes off, giving back about as much experience as the cheapest levels
 * a table offers them at, and anvils forget the work done on it. An
 * enchanted book left with none is a book again. Null if there's nothing
 * to do: one item with nothing to take off, or two that can't combine.
 */
fun Enchantments.grind(first: ItemStack?, second: ItemStack?, random: Random = Random): Ground? {
    if (first == null && second == null) return null
    if ((first?.count ?: 1) != 1 || (second?.count ?: 1) != 1) return null
    var item = first ?: second!!
    var enchantments = item.enchantments
    if (first != null && second != null) {
        if (first.item != second.item || first.item.maxDurability <= 0) return null
        val max = first.item.maxDurability
        item = item.withDamage(max - minOf(max, first.durability + second.durability + max * 5 / 100))
        enchantments = enchantments + second.enchantments.filter { theirs -> enchantments.none { it.id == theirs.id } }
    }
    val (curses, rest) = enchantments.partition { this[it.id]?.curse == true }
    if (rest.isEmpty() && second == null) return null
    val worth = rest.sumOf { enchantment -> this[enchantment.id]?.minCost?.at(enchantment.level) ?: 0 }
    val half = (worth + 1) / 2
    val experience = if (half == 0) 0 else half + random.nextInt(half)
    var output = item.withEnchantments(curses).withRepairCost(0)
    if (output.item.name == ENCHANTED_BOOK && curses.isEmpty()) book?.let { output = output.copy(item = it) }
    return Ground(output, experience)
}

/** What an anvil makes, how many levels it costs, and how many of the material it uses up. */
data class Anvilled(val output: ItemStack, val cost: Int, val materialUsed: Int, val renamedOnly: Boolean)

/** The cost an anvil won't go to outside creative: "Too Expensive!" */
const val ANVIL_TOO_EXPENSIVE = 40

/**
 * What an anvil makes of [input], working in [material] if there's
 * something there, and naming it [name] if that's given, as Bedrock's
 * anvils do. Null if it does nothing.
 *
 * A material that mends the input mends a quarter of it each, a level
 * apiece. The same item that wears out mends it by what that one has left
 * and an eighth more, for two levels. Either that, or an enchanted book,
 * brings its enchantments over: those the input can take, and which go
 * with what it has, each costing its rarity's levels for every level it
 * adds, half that from a book. Two of the same level make one a level
 * higher, up to the enchantment's highest. Each of the material's
 * enchantments that clashes with one on the input costs a level. A new
 * name costs a level. On top, the work done on both before costs what
 * they say, and the result costs more to work again.
 */
fun Enchantments.anvil(input: ItemStack, material: ItemStack?, name: String?): Anvilled? {
    var output = input
    var cost = 0
    var used = 0
    if (material != null) {
        val max = input.item.maxDurability
        if (max > 0 && material.item.name in input.item.repairItems) {
            var mend = minOf(output.damage, max / 4)
            if (mend <= 0) return null
            while (mend > 0 && used < material.count) {
                output = output.withDamage(output.damage - mend)
                cost++
                used++
                mend = minOf(output.damage, max / 4)
            }
        } else {
            val book = material.item.name == ENCHANTED_BOOK && material.enchantments.isNotEmpty()
            if (!book && (input.item != material.item || max <= 0)) return null
            if (max > 0 && !book) {
                val durability = minOf(max, input.durability + material.durability + max * 12 / 100)
                if (durability > input.durability) {
                    output = output.withDamage(max - durability)
                    cost += 2
                }
            }
            val (merged, mergeCost) = mergeEnchantments(input, material, book)
            output = output.withEnchantments(merged)
            cost += mergeCost
            if (input.count > 1) cost = ANVIL_TOO_EXPENSIVE
            used = 1
        }
    }
    val renaming = name != null && name.isNotBlank() && name != (input.customName ?: input.item.displayName)
    if (renaming) {
        output = output.withCustomName(name)
        cost++
    }
    if (output == input || cost <= 0) return null
    val renamedOnly = renaming && material == null
    var total = cost + input.repairCost + (material?.repairCost ?: 0)
    // Renaming alone is never too expensive.
    if (renamedOnly && total >= ANVIL_TOO_EXPENSIVE) total = ANVIL_TOO_EXPENSIVE - 1
    if (!renamedOnly) output = output.withRepairCost(maxOf(output.repairCost, material?.repairCost ?: 0) * 2 + 1)
    return Anvilled(output, total, used, renamedOnly)
}

/**
 * The enchantments [input] ends up with once [material]'s come over, from
 * an enchanted [book] or not, and the levels that costs.
 */
private fun Enchantments.mergeEnchantments(input: ItemStack, material: ItemStack, book: Boolean): Pair<List<EnchantmentLevel>, Int> {
    val merged = input.enchantments.toMutableList()
    var cost = 0
    for (theirs in material.enchantments) {
        val enchantment = this[theirs.id] ?: continue
        var fits = input.item.name == ENCHANTED_BOOK || enchantment.goesOn(input.item)
        for (ours in input.enchantments) {
            val other = this[ours.id] ?: continue
            if (!enchantment.compatibleWith(other)) {
                fits = false
                cost++
            }
        }
        if (!fits) continue
        val existing = merged.find { it.id == theirs.id }
        var level = theirs.level
        if (existing != null) {
            if (existing.level > level || (existing.level == level && level >= enchantment.maxLevel)) continue
            if (existing.level == level) level++
        }
        val multiplier = if (book) maxOf(1, enchantment.anvilMultiplier / 2) else enchantment.anvilMultiplier
        cost += multiplier * (level - (existing?.level ?: 0))
        if (existing != null) merged[merged.indexOf(existing)] = EnchantmentLevel(theirs.id, level) else merged += EnchantmentLevel(theirs.id, level)
    }
    return merged to cost
}

/** Whether [stack] is a book, which an enchanting table turns into an enchanted book. */
internal fun isBook(stack: ItemStack) = stack.item.name == BOOK
