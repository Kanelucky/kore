package org.koremc.registry

import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtMapBuilder
import org.cloudburstmc.nbt.NbtType
import kotlin.random.Random

// Where an item's NBT keeps what's been done to it, as the game names them.
private const val ENCHANTMENTS = "ench"
private const val ENCHANTMENT_ID = "id"
private const val ENCHANTMENT_LEVEL = "lvl"
private const val DAMAGE = "Damage"
private const val REPAIR_COST = "RepairCost"
private const val DISPLAY = "display"
private const val NAME = "Name"
private const val DYED_COLOUR = "customColor"

/** An enchantment at a level, as an item keeps it: by the enchantment's number. */
data class EnchantmentLevel(val id: Int, val level: Int)

/** The enchantments on this item, or that an enchanted book holds, in order. */
val ItemStack.enchantments: List<EnchantmentLevel>
    get() = nbt.getList(ENCHANTMENTS, NbtType.COMPOUND, emptyList()).map {
        EnchantmentLevel(it.getShort(ENCHANTMENT_ID).toInt(), it.getShort(ENCHANTMENT_LEVEL).toInt())
    }

/** This item with [enchantments] instead of what it had, or none. */
fun ItemStack.withEnchantments(enchantments: List<EnchantmentLevel>): ItemStack = withNbt {
    if (enchantments.isEmpty()) {
        remove(ENCHANTMENTS)
    } else {
        putList(ENCHANTMENTS, NbtType.COMPOUND, enchantments.map {
            NbtMap.builder().putShort(ENCHANTMENT_ID, it.id.toShort()).putShort(ENCHANTMENT_LEVEL, it.level.toShort()).build()
        })
    }
}

/** The level of the enchantment numbered [id] on this item, or 0 for none. */
fun ItemStack.levelOf(id: Int): Int = enchantments.firstOrNull { it.id == id }?.level ?: 0

/** How worn this item is: how many of its uses are gone. */
val ItemStack.damage: Int get() = nbt.getInt(DAMAGE, 0)

/** This item worn [damage] uses down, which 0 mends completely. */
fun ItemStack.withDamage(damage: Int): ItemStack = withNbt { if (damage <= 0) remove(DAMAGE) else putInt(DAMAGE, damage) }

/** How many uses this item has left. */
val ItemStack.durability: Int get() = item.maxDurability - damage

/**
 * This item with [uses] more of its uses gone, or null if that wears it
 * out: more gone than its durability, as the game has it, so it works one
 * last time with none left. Unbreaking spares each use with a chance of
 * its level in one more than that, three in four at level 3. An item
 * without durability doesn't wear.
 */
fun ItemStack.worn(uses: Int, random: Random = Random): ItemStack? {
    if (item.maxDurability <= 0 || uses <= 0) return this
    val unbreaking = levelOf(UNBREAKING)
    val lost = (1..uses).count { random.nextInt(unbreaking + 1) == 0 }
    return when {
        lost == 0 -> this
        damage + lost > item.maxDurability -> null
        else -> withDamage(damage + lost)
    }
}

/**
 * This item mended by experience [points], two uses for each, as Mending
 * does with the orbs its player picks up, and how many of the points that
 * took: no more than it takes to mend what's worn.
 */
fun ItemStack.mendedBy(points: Int): Pair<ItemStack, Int> {
    val mended = minOf(damage, points * 2)
    return withDamage(damage - mended) to (mended + 1) / 2
}

/**
 * The levels anvils add to the cost of working this item again, which
 * grows each time one does: the penalty for prior work.
 */
val ItemStack.repairCost: Int get() = nbt.getInt(REPAIR_COST, 0)

fun ItemStack.withRepairCost(cost: Int): ItemStack = withNbt { if (cost <= 0) remove(REPAIR_COST) else putInt(REPAIR_COST, cost) }

/** The name an anvil gave this item, if any. */
val ItemStack.customName: String? get() = nbt.getCompound(DISPLAY, NbtMap.EMPTY).getString(NAME, null)

/** This item called [name], or by its own name again for null. */
fun ItemStack.withCustomName(name: String?): ItemStack = withNbt {
    val display = nbt.getCompound(DISPLAY, NbtMap.EMPTY).toBuilder()
    if (name == null) display.remove(NAME) else display.putString(NAME, name)
    val built = display.build()
    if (built.isEmpty()) remove(DISPLAY) else putCompound(DISPLAY, built)
}

/** The colour leather, or wolf armour, is dyed, opaque, or null for its own. */
val ItemStack.dyedColour: Int? get() = nbt.takeIf { it.containsKey(DYED_COLOUR) }?.getInt(DYED_COLOUR)

/** This item dyed [colour], or back to its own colour for null. */
fun ItemStack.withDyedColour(colour: Int?): ItemStack = withNbt { if (colour == null) remove(DYED_COLOUR) else putInt(DYED_COLOUR, colour) }

/** This item with its NBT changed by [change]. */
private inline fun ItemStack.withNbt(change: NbtMapBuilder.() -> Unit): ItemStack =
    copy(nbt = nbt.toBuilder().apply(change).build())
