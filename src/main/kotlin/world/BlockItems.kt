package org.koremc.world

import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState

/**
 * Items that place a block of another name: crops by their seeds, signs by
 * the wood they're made of, and the like. Their items carry no block state.
 */
private val PLACES = buildMap {
    put("wheat_seeds", "wheat")
    put("pumpkin_seeds", "pumpkin_stem")
    put("melon_seeds", "melon_stem")
    put("beetroot_seeds", "beetroot")
    put("torchflower_seeds", "torchflower_crop")
    put("pitcher_pod", "pitcher_crop")
    put("potato", "potatoes")
    put("carrot", "carrots")
    put("sweet_berries", "sweet_berry_bush")
    put("glow_berries", "cave_vines")
    put("cocoa_beans", "cocoa")
    put("sugar_cane", "reeds")
    put("redstone", "redstone_wire")
    put("repeater", "unpowered_repeater")
    put("comparator", "unpowered_comparator")
    put("string", "trip_wire")
    put("banner", "standing_banner")
    // Oak and dark oak signs are named differently from the rest.
    put("oak_sign", "standing_sign")
    put("dark_oak_sign", "darkoak_standing_sign")
    for (wood in listOf("spruce", "birch", "jungle", "acacia", "mangrove", "poplar", "cherry", "pale_oak", "bamboo", "crimson", "warped")) {
        put("${wood}_sign", "${wood}_standing_sign")
    }
}.entries.associate { (item, block) -> "minecraft:$item" to "minecraft:$block" }

/** Items that place the block of the same name, though they carry no block state of their own. */
private val PLACES_ITSELF = setOf(
    "cauldron", "flower_pot", "hopper", "cake", "campfire", "soul_campfire", "brewing_stand", "frame", "glow_frame",
    "nether_wart", "shelf_mushroom", "nether_sprouts", "kelp", "bed", "straw_bed",
).mapTo(HashSet()) { "minecraft:$it" }

/** Every item that [blockPlacedBy] knows places a block, besides doors and hanging signs. */
internal val BLOCK_ITEMS: Set<String> get() = PLACES.keys + PLACES_ITSELF

/**
 * The block placed by the item called [item] when the item isn't a block
 * itself, or null for an item that places nothing. Doors and hanging signs
 * place the block of the same name, as do a few others, like cauldrons;
 * seeds place their crops, and signs and banners the standing kind, which
 * [wallVariantOf] turns into the kind on a wall.
 */
fun BlockPalette.blockPlacedBy(item: String): BlockState? = when {
    item.endsWith("_door") || item.endsWith("_hanging_sign") || item in PLACES_ITSELF -> defaultOf(item)
    else -> PLACES[item]?.let(::defaultOf)
}

/**
 * The item that places [block], when it's another of a different name:
 * the inverse of [blockPlacedBy], for blocks that drop the item they came
 * from. Null for a block whose item shares its name.
 */
internal fun itemPlacing(block: String): String? = ITEM_PLACING[block]

private val ITEM_PLACING: Map<String, String> = PLACES.entries.associate { (item, block) -> block to item } + mapOf(
    "minecraft:powered_repeater" to "minecraft:repeater",
    "minecraft:powered_comparator" to "minecraft:comparator",
    "minecraft:wall_banner" to "minecraft:banner",
    "minecraft:wall_sign" to "minecraft:oak_sign",
    "minecraft:darkoak_wall_sign" to "minecraft:dark_oak_sign",
) + PLACES.entries.filter { (_, block) -> block.endsWith("_standing_sign") && block != "minecraft:darkoak_standing_sign" }
    .associate { (item, block) -> block.replace("_standing_sign", "_wall_sign") to item }

/**
 * The kind of [block] that goes on a wall, for blocks that come in a kind
 * that stands and a kind on a wall: signs, banners and coral fans. Null for
 * a block that doesn't.
 */
internal fun BlockPalette.wallVariantOf(block: BlockState): BlockState? {
    val name = block.name
    val wall = when {
        name == "minecraft:standing_sign" -> "minecraft:wall_sign"
        name.endsWith("_standing_sign") -> name.replace("_standing_sign", "_wall_sign")
        name == "minecraft:standing_banner" -> "minecraft:wall_banner"
        name.endsWith("_coral_fan") -> name.replace("_coral_fan", "_coral_wall_fan")
        else -> return null
    }
    return defaultOf(wall)
}
