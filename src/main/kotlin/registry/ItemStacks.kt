package org.koremc.registry

import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType

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
     * An item that wears has no variants, so [aux] is how worn it is, as
     * `/give` has it.
     */
    fun of(name: String, count: Int = 1, aux: Int = 0): ItemStack? {
        val item = byName[name] ?: return null
        if (item.maxDurability > 0) return ItemStack(item, count).withDamage(aux)
        val shown = inCreative[name].orEmpty()
        val block = (shown.find { it.aux == aux } ?: shown.find { it.block != null })?.block ?: blocks.defaultOf(name)
        return ItemStack(item, count, aux, block)
    }

    /**
     * What [tag] keeps, as the game keeps an item in NBT, like one inside a
     * shulker box's item, with the slot it's in. Null for an item there's
     * no such.
     */
    fun fromNbt(tag: NbtMap): Pair<Int, ItemStack>? {
        val name = tag.getString("Name")
        val stack = of(name, tag.getByte("Count").toInt() and 0xFF, tag.getShort("Damage").toInt()) ?: return null
        val block = tag.getCompound("Block").takeIf { it.isNotEmpty() }?.let { blocks.find(it.getString("name"), it.getCompound("states")) }
        return tag.getByte("Slot").toInt() to stack.copy(block = block ?: stack.block, nbt = tag.getCompound("tag"))
    }

    companion object {
        /** [stack] as the game keeps an item in NBT, in [slot] of whatever holds it. */
        fun nbtOf(stack: ItemStack, slot: Int): NbtMap {
            val tag = NbtMap.builder()
                .putString("Name", stack.item.name)
                .putByte("Count", stack.count.toByte())
                .putShort("Damage", stack.aux.toShort())
                .putByte("Slot", slot.toByte())
                .putByte("WasPickedUp", 0)
            stack.block?.let { tag.putCompound("Block", NbtMap.builder().putString("name", it.name).putCompound("states", it.properties).putInt("version", it.version).build()) }
            if (stack.nbt.isNotEmpty()) tag.putCompound("tag", stack.nbt)
            return tag.build()
        }

        /** The NBT of an item that holds [items], each in its slot, as a shulker box's does. */
        fun holding(items: List<Pair<Int, ItemStack>>): NbtMap =
            NbtMap.builder().putList(ITEMS, NbtType.COMPOUND, items.map { (slot, stack) -> nbtOf(stack, slot) }).build()

        /** Where an item's NBT keeps the items it holds. */
        const val ITEMS = "Items"
    }
}
