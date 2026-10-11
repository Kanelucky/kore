package org.koremc.registry

/** The creative inventory's tabs. Each is sent as its number. */
enum class CreativeCategory(val id: Int) {
    CONSTRUCTION(1),
    NATURE(2),
    EQUIPMENT(3),
    ITEMS(4),
}

/**
 * A group of items in a creative tab, which folds up behind [icon]. [name]
 * is a translation key, like `itemGroup.name.planks`. A group without a
 * name or icon holds items that sit loose in the tab.
 */
data class CreativeGroup(val category: CreativeCategory, val name: String, val icon: ItemStack?)

/** An item in the creative inventory, in the group at index [group]. */
data class CreativeItem(val stack: ItemStack, val group: Int)

/** Every creative tab's groups, and every item in them, in the order the game shows them. */
data class CreativeInventory(val groups: List<CreativeGroup>, val items: List<CreativeItem>)
