package org.koremc.registry

import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtMapBuilder
import org.cloudburstmc.nbt.NbtType

// Where an item's NBT keeps what's been done to it, as the game names them.
private const val ENCHANTMENTS = "ench"
private const val ENCHANTMENT_ID = "id"
private const val ENCHANTMENT_LEVEL = "lvl"
private const val DAMAGE = "Damage"
private const val REPAIR_COST = "RepairCost"
private const val DISPLAY = "display"
private const val NAME = "Name"

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

/** How worn this item is: how many of its uses are gone. */
val ItemStack.damage: Int get() = nbt.getInt(DAMAGE, 0)

/** This item worn [damage] uses down, which 0 mends completely. */
fun ItemStack.withDamage(damage: Int): ItemStack = withNbt { if (damage <= 0) remove(DAMAGE) else putInt(DAMAGE, damage) }

/** How many uses this item has left. */
val ItemStack.durability: Int get() = item.maxDurability - damage

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

/** This item with its NBT changed by [change]. */
private inline fun ItemStack.withNbt(change: NbtMapBuilder.() -> Unit): ItemStack =
    copy(nbt = nbt.toBuilder().apply(change).build())
