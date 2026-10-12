package org.koremc.registry

import org.cloudburstmc.nbt.NbtMap

/** How the client builds an item: from its legacy built-in definition, from data-driven components, or neither. Sent as its ordinal. */
enum class ItemVersion { LEGACY, DATA_DRIVEN, NONE }

/**
 * An item as the client's item registry lists it. From then on, items
 * travel by [runtimeId], and that list is the only place the client learns
 * which ID means which item.
 */
data class ItemDefinition(
    /** Its identifier, like `minecraft:stick`. */
    val name: String,
    /** Fits in 16 bits. Items that place blocks have IDs below 256, often negative ones. */
    val runtimeId: Int,
    /** Whether components define the item, rather than the game's code. */
    val componentBased: Boolean,
    val version: ItemVersion,
    /** The item's component data, which most items leave empty. */
    val components: NbtMap = NbtMap.EMPTY,
    /** The most of this item one slot holds. Data-driven items say; for the rest, the game's item data does. */
    val maxStackSize: Int = components.maxStackSize() ?: 64,
    /** What the item is called in English, like `Diamond Sword`. */
    val displayName: String = displayNameOf(name),
)

/** The most of an item one slot holds, if its [components][ItemDefinition.components] say. */
internal fun NbtMap.maxStackSize(): Int? =
    getCompound("components", NbtMap.EMPTY).getCompound("item_properties", NbtMap.EMPTY).takeIf { it.containsKey("max_stack_size") }?.getInt("max_stack_size")

/** A name for the item called [name] when the game's data has none: `minecraft:black_wool_slab` is `Black Wool Slab`. */
internal fun displayNameOf(name: String) = name.substringAfter(':').split('_').joinToString(" ") { word -> word.replaceFirstChar(Char::uppercaseChar) }

/**
 * Some of an item, as inventories and the creative inventory hold it: how
 * many, which variant (its aux value, like an arrow's potion), the block
 * state it places, and its NBT, like an enchanted book's enchantment.
 */
data class ItemStack(
    val item: ItemDefinition,
    val count: Int = 1,
    val aux: Int = 0,
    val block: BlockState? = null,
    val nbt: NbtMap = NbtMap.EMPTY,
)

/** Whether [other] is the same item, variant, block and NBT as this, so the two could share a slot. */
fun ItemStack.sameKindAs(other: ItemStack) = item == other.item && aux == other.aux && block == other.block && nbt == other.nbt
