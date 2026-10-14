package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.registry.BlockState
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemStacks

internal const val JUKEBOX = "minecraft:jukebox"

/** Where a jukebox keeps its disc. */
private const val RECORD = "RecordItem"

/** How many times blocks tick a second. */
private const val TICKS_PER_SECOND = 20

/** A music disc: the power a comparator reads from a jukebox playing it, and how long its song is, in seconds. */
private class Disc(val power: Int, val seconds: Int)

/** Every music disc, by the name its item and song share after "music_disc_". */
private val DISCS = mapOf(
    "13" to Disc(1, 178), "cat" to Disc(2, 185), "blocks" to Disc(3, 345), "chirp" to Disc(4, 185), "far" to Disc(5, 174),
    "mall" to Disc(6, 197), "mellohi" to Disc(7, 96), "stal" to Disc(8, 150), "bounce" to Disc(8, 234), "strad" to Disc(9, 188),
    "lava_chicken" to Disc(9, 135), "ward" to Disc(10, 251), "tears" to Disc(10, 175), "11" to Disc(11, 71),
    "creator_music_box" to Disc(11, 73), "wait" to Disc(12, 237), "creator" to Disc(12, 176), "pigstep" to Disc(13, 148),
    "precipice" to Disc(13, 299), "otherside" to Disc(14, 195), "relic" to Disc(14, 219), "5" to Disc(15, 178),
)

private const val DISC_PREFIX = "minecraft:music_disc_"

/** The song of the disc called [item], by its name after "music_disc_", or null for an item that isn't a disc. */
private fun songOf(item: String) = item.takeIf { it.startsWith(DISC_PREFIX) }?.removePrefix(DISC_PREFIX)?.takeIf { it in DISCS }

val BlockState.isJukebox get() = name == JUKEBOX

/** The block entity of a jukebox holding [disc], or none. */
internal fun jukeboxData(disc: ItemStack? = null): NbtMap {
    val data = NbtMap.builder().putString("id", "Jukebox")
    disc?.let { data.putCompound(RECORD, ItemStacks.nbtOf(it.copy(count = 1), 0).toBuilder().apply { remove("Slot") }.build()) }
    return data.build()
}

/** The disc in the jukebox at [pos], made with [items], or null if there's none. */
fun FlatWorld.discIn(pos: BlockPos, items: ItemStacks): ItemStack? = blockEntityAt(pos)?.getCompound(RECORD, null)?.let(items::fromNbt)?.second

/**
 * The game's key for the title of the song on the disc in the jukebox at
 * [pos], as the client looks it up, or null if there's none.
 */
fun FlatWorld.songTitleIn(pos: BlockPos): String? {
    val disc = blockEntityAt(pos)?.getCompound(RECORD, null)?.getString("Name", null) ?: return null
    return songOf(disc)?.let { "%item.record_$it.desc" }
}

/**
 * A jukebox at [pos] starting a song, [song], the game's name for its
 * sound, or stopping it, for null, which clients know by [handle] from the
 * moment it starts.
 */
data class SongChange(val pos: BlockPos, val handle: Long, val song: String? = null)

/** A song a jukebox is playing: the block tick it ends at, and the handle clients know it by. */
internal class PlayingSong(val endsAt: Long, val handle: Long)

/**
 * Uses [held] on the jukebox at [pos], as a player clicking it does: a
 * disc goes into an empty one and plays, giving power until its song ends,
 * and a click on one with a disc pops the disc out of its top, whatever's
 * held, and stops the song. Null for an empty jukebox clicked with
 * anything but a disc. Discs are made with [items].
 */
fun FlatWorld.useJukebox(pos: BlockPos, held: ItemStack?, items: ItemStacks): ItemUsedOn? {
    discIn(pos, items)?.let { disc ->
        val stopped = stopSong(pos)
        setBlockEntity(pos, jukeboxData(null))
        return ItemUsedOn(listOf(pos), dropped = listOf(disc), dropAt = pos.side(Face.UP), songs = listOfNotNull(stopped))
    }
    val song = held?.let { songOf(it.item.name) } ?: return null
    return ItemUsedOn(listOf(pos), held = HeldAfter.USED_UP, songs = listOf(playDisc(pos, held, song)))
}

/**
 * Puts one of [stack] into the jukebox at [pos] from a hopper, if it's a
 * disc and the jukebox is empty, and plays it, its song going in [songs].
 * Returns whether it went in.
 */
internal fun FlatWorld.discFromHopper(pos: BlockPos, stack: ItemStack, songs: MutableList<SongChange>): Boolean {
    if (stateAt(pos)?.isJukebox != true || blockEntityAt(pos)?.containsKey(RECORD) == true) return false
    val song = songOf(stack.item.name) ?: return false
    songs += playDisc(pos, stack, song)
    return true
}

/** Puts [disc] in the jukebox at [pos] and plays its [song], which gives power until it ends. Returns the song starting. */
private fun FlatWorld.playDisc(pos: BlockPos, disc: ItemStack, song: String): SongChange {
    setBlockEntity(pos, jukeboxData(disc))
    val ticks = DISCS.getValue(song).seconds * TICKS_PER_SECOND
    val handle = newSoundHandle()
    playing[pos] = PlayingSong(blockTicks + ticks, handle)
    schedule(pos, ticks, BlockUpdate.SONG)
    powerChanges += pos
    return SongChange(pos, handle, "record.$song")
}

/** Stops the song the jukebox at [pos] is playing, if it is, and the power it gives. Returns the song stopping, if there was one. */
internal fun FlatWorld.stopSong(pos: BlockPos): SongChange? {
    val stopped = playing.remove(pos) ?: return null
    powerChanges += pos
    return SongChange(pos, stopped.handle)
}

/** Ends the song of the jukebox at [pos], once its time is up, so it gives no more power. Its disc stays in. */
internal fun FlatWorld.endSong(pos: BlockPos) {
    val song = playing[pos] ?: return
    if (song.endsAt <= blockTicks) stopSong(pos)
}

/** Whether the jukebox at [pos] is playing a song, which gives full power all round it. */
internal fun FlatWorld.playingSong(pos: BlockPos) = pos in playing

/** The power a comparator reads from the jukebox at [pos]: the disc's own, while it has one, playing or not. */
internal fun FlatWorld.discPower(pos: BlockPos): Int {
    val disc = blockEntityAt(pos)?.getCompound(RECORD, null)?.getString("Name", null) ?: return 0
    return songOf(disc)?.let { DISCS.getValue(it).power } ?: 0
}
