package org.koremc.world

import org.koremc.registry.BlockState

/**
 * How the names of blocks that aren't whole cubes end: slabs, stairs,
 * fences and the like come in families named alike. Cloudburst's data has
 * no block shapes, so Kore goes by name.
 */
private val PARTIAL_ENDINGS = listOf(
    "_slab", "_stairs", "_wall", "fence", "fence_gate", "_pane", "_bars", "door", "button", "pressure_plate",
    "carpet", "rail", "torch", "lantern", "chain", "candle", "cake", "_sign", "banner", "_bed", "_head", "_skull",
    "chest", "anvil", "_shelf", "golem_statue", "lightning_rod", "sapling", "_tulip", "_mushroom", "_fungus",
    "_vines", "_petals", "_bush", "_grass", "_plant", "dripleaf", "_dripleaf_block", "blossom", "_flower",
    "_propagule", "_bud", "_cluster", "coral", "_fan", "_egg", "_spike", "_spawn", "detector",
)

/** Blocks that aren't whole cubes and have names of their own. */
private val PARTIAL_NAMES = setOf(
    "bed", "bamboo", "bell", "brewing_stand", "cactus", "campfire", "soul_campfire", "cauldron", "composter",
    "conduit", "daylight_detector_inverted", "decorated_pot", "dried_ghast", "enchanting_table", "end_portal_frame",
    "end_rod", "flower_pot", "glow_lichen", "grindstone", "heavy_core", "hopper", "ladder", "lectern", "lever",
    "scaffolding", "sculk_sensor", "calibrated_sculk_sensor", "sculk_shrieker", "sculk_vein", "resin_clump",
    "pointed_dripstone", "sea_pickle", "stonecutter_block", "tripwire_hook", "waterlily", "web",
    // Plants
    "large_fern", "leaf_litter", "pale_hanging_moss", "wildflowers", "allium", "azure_bluet", "blue_orchid",
    "cornflower", "dandelion", "golden_dandelion", "lily_of_the_valley", "oxeye_daisy", "poppy", "torchflower",
    "wither_rose", "lilac", "peony", "sunflower",
)

/** Whole cubes whose names end like those of a family that isn't. */
private val FULL_NAMES = setOf("sea_lantern", "dirt_with_roots", "mangrove_roots", "muddy_mangrove_roots")

/**
 * Whether this block is a whole cube, which can hold things like torches
 * on any side. Double slabs are, as are blocks just short of a cube that
 * still count as solid, like soul sand and farmland. Blocks that a placed
 * block replaces, like air and short grass, aren't.
 */
val BlockState.isFullCube: Boolean
    get() {
        val name = name.removePrefix("minecraft:")
        if (name in FULL_NAMES || "double" in name) return true
        return !replaceable && name !in PARTIAL_NAMES && PARTIAL_ENDINGS.none(name::endsWith)
    }

/** The way stairs climb, by their `weirdo_direction`. Their back is on the side they climb towards. */
internal val STAIRS_CLIMB = listOf(Face.EAST, Face.WEST, Face.SOUTH, Face.NORTH)

/** The corner stairs turn: none, or an inner or outer one, to their left or right as they climb. */
internal const val STAIRS_CORNER = "minecraft:corner"

/**
 * Whether this block's [face] is whole and flat, so that torches, buttons,
 * levers and ladders can be fixed to it. Every face of a whole cube is. Of
 * the rest, a slab's is on the side it fills, and stairs have whole
 * bottoms, or tops when they're upside down. Straight stairs have a whole
 * back too, and stairs filling an inner corner have whole sides on both
 * the back and the side the corner turns to. Stairs rounding an outer
 * corner have no whole side. A hopper's top counts as whole, as Bedrock
 * has it, bowl and all, so levers and the like go on it.
 */
fun BlockState.hasFullFace(face: Face): Boolean {
    if (isFullCube) return true
    if (name == HOPPER) return face == Face.UP
    when (properties["minecraft:vertical_half"]) {
        "top" -> return face == Face.UP
        "bottom" -> return face == Face.DOWN
    }
    val climb = (properties["weirdo_direction"] as? Int)?.let(STAIRS_CLIMB::getOrNull) ?: return false
    val upsideDown = properties["upside_down_bit"] == 1.toByte()
    val wholeSides = when (properties[STAIRS_CORNER] ?: "none") {
        "none" -> listOf(climb)
        "inner_left" -> listOf(climb, climb.counterClockwise)
        "inner_right" -> listOf(climb, climb.clockwise)
        else -> emptyList()
    }
    return face == (if (upsideDown) Face.UP else Face.DOWN) || face in wholeSides
}

/** Whether a torch can stand on this block: one with a whole top can hold it, and so can a fence or wall post. */
val BlockState.holdsTorchOnTop get() = hasFullFace(Face.UP) || name.endsWith("fence") || name.endsWith("_wall")
