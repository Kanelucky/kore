package org.koremc.registry

/**
 * Makes stacks of items by name, as the game hands them out. A block's
 * item carries the block state it places, which the creative inventory
 * says, or else the block's default. They come without NBT, the way a new
 * item does: an enchanted book with no enchantment, a banner with no
 * pattern.
 */
class ItemStacks(items: List<ItemDefinition>, creative: CreativeInventory, private val blocks: BlockPalette) {
    private val byName = items.associateBy { it.name }
    private val inCreative = creative.items.map { it.stack }.groupBy { it.item.name }

    /** Every item, by name. */
    val names: Set<String> get() = byName.keys

    /** The item called [name], like `minecraft:stick`, or null if there's no such item. */
    operator fun get(name: String): ItemDefinition? = byName[name]

    /**
     * [count] of the item called [name], in the variant numbered [aux], like
     * a potion's. Null if there's no such item. A block's item places the
     * block state the creative inventory gives that variant, or its first.
     */
    fun of(name: String, count: Int = 1, aux: Int = 0): ItemStack? {
        val item = byName[name] ?: return null
        val shown = inCreative[name].orEmpty()
        val block = (shown.find { it.aux == aux } ?: shown.find { it.block != null })?.block ?: blocks.defaultOf(name)
        return ItemStack(item, count, aux, block)
    }
}
