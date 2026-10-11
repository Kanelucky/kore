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
)
