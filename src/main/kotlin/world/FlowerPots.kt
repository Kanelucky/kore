package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.registry.BlockState
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemStacks

private const val FLOWER_POT = "minecraft:flower_pot"

/** What a pot keeps: the plant in it, and the bit the game sets while there's one. */
private const val PLANT = "PlantBlock"
private const val HOLDS_PLANT = "update_bit"

/** Plants that go in a flower pot, by their names besides those of every sapling, tulip and the like. */
private val POTTED = setOf(
    "dandelion", "golden_dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy", "cornflower", "lily_of_the_valley",
    "wither_rose", "torchflower", "open_eyeblossom", "closed_eyeblossom", "red_mushroom", "brown_mushroom", "crimson_fungus",
    "warped_fungus", "crimson_roots", "warped_roots", "fern", "deadbush", "cactus", "bamboo", "azalea", "flowering_azalea",
    "mangrove_propagule",
).mapTo(HashSet()) { "minecraft:$it" }

/** Whether [plant] goes in a flower pot. */
private fun potted(plant: BlockState) = plant.name in POTTED || plant.name.endsWith("_sapling") || plant.name.endsWith("_tulip")

/** Whether this is a flower pot. */
val BlockState.isFlowerPot get() = name == FLOWER_POT

/** The plant in the pot at [pos], or null for an empty pot. */
fun FlatWorld.plantInPot(pos: BlockPos): BlockState? {
    val plant = blockEntityAt(pos)?.getCompound(PLANT, null) ?: return null
    return blocks.find(plant.getString("name", ""), plant.getCompound("states", NbtMap.EMPTY))
}

/**
 * Uses [held], which places [plant], on the flower pot at [pos], [state], as
 * a player clicking it does: an empty pot takes a plant that goes in pots,
 * using it up, and an empty hand takes the plant out of a full one, made
 * with [items]. A full pot takes any other click too, doing nothing, so
 * what's held isn't placed beside it. Null for an empty pot clicked with
 * anything but a plant for it.
 */
fun FlatWorld.usePot(pos: BlockPos, state: BlockState, held: ItemStack?, plant: BlockState?, items: ItemStacks): ItemUsedOn? {
    val inPot = plantInPot(pos)
    return when {
        inPot == null && plant != null && potted(plant) -> {
            blocks.find(FLOWER_POT, state.properties + (HOLDS_PLANT to bit(true)))?.let { set(pos, it) }
            setBlockEntity(pos, NbtMap.builder().putString("id", "FlowerPot").putCompound(PLANT, blockStateNbt(plant)).build())
            ItemUsedOn(listOf(pos), held = HeldAfter.USED_UP)
        }
        inPot != null && held == null -> {
            blocks.find(FLOWER_POT, state.properties + (HOLDS_PLANT to bit(false)))?.let { set(pos, it) }
            setBlockEntity(pos, NbtMap.builder().putString("id", "FlowerPot").build())
            ItemUsedOn(listOf(pos), given = items.of(inPot.name))
        }
        inPot != null -> ItemUsedOn(emptyList())
        else -> null
    }
}
