package org.koremc.protocol

import io.netty.buffer.ByteBuf
import org.koremc.world.BlockPos

private const val CLIENTBOUND_MAP_ITEM_DATA = 67
internal const val MAP_INFO_REQUEST = 68
internal const val MAP_CREATE_LOCKED_COPY = 131

/** The client asking what the map numbered [mapId] shows, as it does when a player holds one it hasn't seen. */
data class MapInfoRequest(val mapId: Long) : ClientPacket

/**
 * The client picking the number [copyId] for the locked copy of the map
 * numbered [mapId] a cartography table would make, which it does as a
 * glass pane goes in beside the map, and names the copy it shows by.
 */
data class MapCreateLockedCopy(val mapId: Long, val copyId: Long) : ClientPacket

internal fun decodeMapCreateLockedCopy(buffer: ByteBuf) = MapCreateLockedCopy(buffer.readVarLong(), buffer.readVarLong())

internal fun decodeMapInfoRequest(buffer: ByteBuf): MapInfoRequest {
    val mapId = buffer.readVarLong()
    // Then the pixels the client already has, a colour and an index each, which Kore sends whole anyway.
    val pixels = buffer.readIntLE()
    if (pixels < 0 || pixels > MAP_PIXELS) throw PacketException("$pixels pixels in a map info request")
    buffer.skipBytes(pixels * 6)
    return MapInfoRequest(mapId)
}

private const val MAP_PIXELS = 128 * 128

/** What a map tracks, when it's an entity rather than a block. */
private const val TRACKED_ENTITY = 0

/**
 * A mark drawn on a map, like a player's pointer: of [type], turned
 * [rotation] sixteenths of a circle from south, at [x] and [y], counted in
 * half pixels from the middle of the map.
 */
data class MapMarker(val type: Int, val rotation: Int, val x: Int, val y: Int) {
    companion object {
        /** A white pointer, where a player is on the map. */
        const val PLAYER = 0

        /** A white dot on the edge, for a player off the map, but not far off. */
        const val PLAYER_OFF_MAP = 6
    }
}

/** A square of a map's pixels, [width] by [height] from [x] and [y], row by row, each as ARGB, or 0 for none drawn yet. */
class MapPatch(val x: Int, val y: Int, val width: Int, val height: Int, val pixels: IntArray)

/**
 * What the map numbered [mapId] shows, for the client to draw: the area
 * around [centre] at [scale], whether it's locked, the [markers] on it,
 * if it has any, and the pixels in [patch], if any changed. It's [partOf]
 * the maps it zooms out to, which the client learns of with it.
 */
data class ClientboundMapItemData(
    val mapId: Long,
    val centre: BlockPos,
    val scale: Int,
    val locked: Boolean,
    val markers: List<MapMarker>? = null,
    val patch: MapPatch? = null,
    /** The map itself, then the one it zooms out to, and so on to the furthest out. */
    val partOf: List<Long> = listOf(mapId),
) : ServerPacket {
    override val id get() = CLIENTBOUND_MAP_ITEM_DATA

    override fun encodeBody(out: ByteBuf) {
        out.writeVarLong(mapId)
        out.writeByte(0) // The overworld
        out.writeBoolean(locked)
        out.writeBlockPos(centre)
        // The client won't do without these: the map itself, at least.
        out.writeBoolean(true).writeVarUInt(partOf.size)
        partOf.forEach { out.writeVarLong(it) }
        out.writeBoolean(true).writeByte(scale)
        // The client draws a marker only for something it tracks on the map, so each has an entity of its own,
        // numbered from 0, which it has no other way to know and so leaves where the marker puts it.
        out.writeBoolean(markers != null)
        markers?.let {
            out.writeVarUInt(it.size)
            for (index in it.indices) out.writeIntLE(TRACKED_ENTITY).writeBoolean(true).writeVarLong(index.toLong()).writeBoolean(false)
        }
        out.writeBoolean(markers != null)
        markers?.let {
            out.writeVarUInt(it.size)
            for (marker in it) {
                out.writeByte(marker.type).writeByte(marker.rotation).writeByte(marker.x).writeByte(marker.y)
                out.writeString("")
                out.writeIntLE(-1) // White, which the client leaves them
            }
        }
        val patch = patch
        out.writeBoolean(patch != null)
        if (patch == null) {
            repeat(4) { out.writeBoolean(false) } // Its height and where it starts, then its pixels
            return
        }
        out.writeVarInt(patch.width)
        out.writeBoolean(true).writeVarInt(patch.height)
        out.writeBoolean(true).writeVarInt(patch.x)
        out.writeBoolean(true).writeVarInt(patch.y)
        out.writeBoolean(true).writeVarUInt(patch.pixels.size)
        // The client takes each pixel as its red, green, blue and alpha bytes.
        for (argb in patch.pixels) out.writeIntLE((argb and 0xFF00FF00.toInt()) or ((argb shr 16) and 0xFF) or ((argb and 0xFF) shl 16))
    }
}
