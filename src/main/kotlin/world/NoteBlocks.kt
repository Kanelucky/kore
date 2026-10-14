package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.registry.AIR
import org.koremc.registry.BlockState

internal const val NOTE_BLOCK = "minecraft:noteblock"

// What a note block keeps: the note it's tuned to, and whether it was powered when redstone last looked.
private const val NOTE = "note"
private const val POWERED = "powered"

/** How many notes a note block plays, two octaves, which a click goes up through and round again. */
private const val NOTES = 25

/** The game's numbers for the instruments a note block plays, by the block it stands on. */
private const val HARP = 0
private const val BASS_DRUM = 1
private const val SNARE = 2
private const val HAT = 3
private const val BASS = 4
private const val FLUTE = 5
private const val BELL = 6
private const val GUITAR = 7
private const val CHIME = 8
private const val XYLOPHONE = 9
private const val IRON_XYLOPHONE = 10
private const val COW_BELL = 11
private const val DIDGERIDOO = 12
private const val BIT = 13
private const val BANJO = 14
private const val PLING = 15

/** The game's name for a note block's note, with the instrument and note as its data. */
internal const val NOTE_SOUND = "note"

/** A note block at [pos] playing [note] on [instrument]: what players hear, and the note they see rise out of it. */
data class NotePlayed(val pos: BlockPos, val instrument: Int, val note: Int)

val BlockState.isNoteBlock get() = name == NOTE_BLOCK

/** The block entity of a new note block, tuned to its lowest note. */
internal fun noteBlockData(): NbtMap = NbtMap.builder().putString("id", "Music").putByte(NOTE, 0).putByte(POWERED, 0).build()

/** The note block at [pos]'s block entity, or a new one's. */
private fun FlatWorld.noteData(pos: BlockPos): NbtMap = blockEntityAt(pos) ?: noteBlockData()

/**
 * Plays the note block at [pos], if the space above it is clear, on the
 * instrument the block below it makes it. Null if it's no note block, or
 * something's on it.
 */
internal fun FlatWorld.playNote(pos: BlockPos): NotePlayed? {
    if (stateAt(pos)?.isNoteBlock != true || stateAt(pos.side(Face.UP))?.name != AIR) return null
    return NotePlayed(pos, instrumentOn(stateAt(pos.side(Face.DOWN))), noteData(pos).getByte(NOTE).toInt())
}

/**
 * Tunes the note block at [pos] a note up, round to the lowest after the
 * highest, as a click does, and plays it, if it can. Returns the note it
 * played, or null if it couldn't play.
 */
internal fun FlatWorld.tuneNote(pos: BlockPos): NotePlayed? {
    val data = noteData(pos)
    setBlockEntity(pos, data.toBuilder().putByte(NOTE, ((data.getByte(NOTE) + 1) % NOTES).toByte()).build())
    return playNote(pos)
}

/**
 * Notes whether power reaches the note block at [pos], [powered], and plays
 * it as power arrives. Returns the note it played, if any.
 */
internal fun FlatWorld.powerNote(pos: BlockPos, powered: Boolean): NotePlayed? {
    val data = noteData(pos)
    if ((data.getByte(POWERED) == 1.toByte()) == powered) return null
    setBlockEntity(pos, data.toBuilder().putByte(POWERED, bit(powered)).build())
    return if (powered) playNote(pos) else null
}

/** The kinds of wood, by how the names of the blocks made of them start. */
private val WOODS = listOf(
    "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "darkoak", "mangrove", "cherry", "pale_oak", "crimson", "warped",
    "bamboo", "wooden",
)

/** What wooden blocks are, by how their names end after the kind of wood. */
private val WOODEN_PARTS = setOf(
    "planks", "log", "wood", "stem", "hyphae", "block", "mosaic", "stairs", "slab", "double_slab", "mosaic_stairs", "mosaic_slab",
    "mosaic_double_slab", "fence", "fence_gate", "door", "trapdoor", "pressure_plate", "button", "standing_sign", "wall_sign",
    "hanging_sign", "shelf",
)

/** Wooden blocks whose names don't start with a kind of wood. */
private val WOODEN = setOf(
    "trapdoor", "fence_gate", "standing_sign", "wall_sign", "chest", "trapped_chest", "barrel", "crafting_table", "cartography_table",
    "fletching_table", "smithing_table", "loom", "campfire", "soul_campfire", "composter", "noteblock", "jukebox", "bookshelf",
    "chiseled_bookshelf", "lectern", "bee_nest", "beehive", "brown_mushroom_block", "red_mushroom_block", "mushroom_stem",
    "creaking_heart", "standing_banner", "wall_banner", "daylight_detector", "daylight_detector_inverted",
)

/** Stone and the like, by words in their names, which play the bass drum. */
private val STONY = listOf(
    "stone", "cobble", "granite", "diorite", "andesite", "deepslate", "tuff", "prismarine", "netherrack", "basalt", "brick", "purpur",
    "quartz", "nylium", "concrete", "terracotta", "magma", "obsidian", "bedrock", "coral", "_ore", "raw_iron_block", "raw_gold_block",
    "raw_copper_block", "ancient_debris", "amethyst_block", "furnace", "smoker", "dispenser", "dropper", "crafter", "observer",
    "respawn_anchor", "ender_chest", "enchanting_table", "vault", "end_portal_frame", "mob_spawner", "trial_spawner", "dragon_egg",
)

/** Blocks with stone in their names that play the harp all the same. */
private val NOT_STONY = setOf("lodestone", "grindstone", "pointed_dripstone")

/** Blocks with an instrument of their own, by name. */
private val OWN_INSTRUMENTS = mapOf(
    "clay" to FLUTE, "honeycomb_block" to FLUTE, "resin_block" to FLUTE, "gold_block" to BELL, "packed_ice" to CHIME,
    "bone_block" to XYLOPHONE, "iron_block" to IRON_XYLOPHONE, "soul_sand" to COW_BELL, "pumpkin" to DIDGERIDOO, "emerald_block" to BIT,
    "hay_block" to BANJO, "glowstone" to PLING, "sea_lantern" to HAT, "beacon" to HAT, "heavy_core" to SNARE, "gravel" to SNARE,
    "suspicious_gravel" to SNARE, "sand" to SNARE, "red_sand" to SNARE, "suspicious_sand" to SNARE,
)

/**
 * The instrument a note block on [below] plays, as the game picks it:
 * wood plays the bass, stone and the like the bass drum, sand and gravel
 * the snare, glass the hat, wool the guitar, and so on, and anything else
 * the harp.
 */
internal fun instrumentOn(below: BlockState?): Int {
    val name = below?.name?.removePrefix("minecraft:") ?: return HARP
    OWN_INSTRUMENTS[name]?.let { return it }
    val bare = name.removePrefix("stripped_")
    return when {
        name.endsWith("concrete_powder") -> SNARE
        name.endsWith("glass") || name.endsWith("glass_pane") -> HAT
        name.endsWith("_wool") -> GUITAR
        name.startsWith("infested_") -> FLUTE
        bare in WOODEN || WOODS.any { bare.startsWith("${it}_") && bare.removePrefix("${it}_") in WOODEN_PARTS } -> BASS
        name !in NOT_STONY && ("redstone" !in name || name.endsWith("_ore")) && STONY.any(name::contains) -> BASS_DRUM
        else -> HARP
    }
}
