package org.koremc.protocol

import io.netty.buffer.ByteBuf
import org.koremc.world.BlockPos
import org.koremc.world.Vec3

private const val PLAY_SOUND = 86
private const val UPDATE_SOUND_DATA = 348
private const val RECORD_STARTED = 352

/** How finely a sound's position goes on the wire: in eighths of a block. */
private const val SOUND_POSITION_SCALE = 8

/** The kind of change to a sound that stops it, the first of those [UpdateSoundData] can make. */
private const val STOP = 0

/** How many changes to a sound [UpdateSoundData] carries: whether it stops, its volume, pitch, fade, place in it, pausing and resuming. */
private const val SOUND_CHANGES = 7

/**
 * Plays the sound the game calls [sound], like `record.cat`, at
 * [position], at [volume] and [pitch]. A sound played with a [handle] can
 * be stopped by it later, with [UpdateSoundData].
 */
data class PlaySound(val sound: String, val position: Vec3, val volume: Float, val pitch: Float, val handle: Long? = null) : ServerPacket {
    override val id get() = PLAY_SOUND

    override fun encodeBody(out: ByteBuf) {
        out.writeString(sound)
        out.writeBlockPos(BlockPos((position.x * SOUND_POSITION_SCALE).toInt(), (position.y * SOUND_POSITION_SCALE).toInt(), (position.z * SOUND_POSITION_SCALE).toInt()))
        out.writeFloatLE(volume)
        out.writeFloatLE(pitch)
        out.writeVarInt(0) // How many times it plays again
        out.writeBoolean(false) // Whether players out of range hear it
        out.writeBoolean(handle != null)
        handle?.let(out::writeLongLE)
        out.writeBoolean(false) // A place in the sound to start from
    }
}

/** Tells the client the jukebox at [position] has started the song it plays as [handle], which it ties to the jukebox. */
data class RecordStarted(val position: BlockPos, val handle: Long) : ServerPacket {
    override val id get() = RECORD_STARTED

    override fun encodeBody(out: ByteBuf) {
        out.writeBlockPos(position)
        out.writeLongLE(handle)
    }
}

/** Stops the sound played as [handle], as a jukebox's song stops. The packet can change a sound in other ways, which Kore doesn't need. */
data class UpdateSoundData(val handle: Long) : ServerPacket {
    override val id get() = UPDATE_SOUND_DATA

    override fun encodeBody(out: ByteBuf) {
        out.writeLongLE(handle)
        // Each change names its kind first; the kind that stops a sound has nothing more, and stands for no change in the rest.
        repeat(SOUND_CHANGES) { out.writeVarUInt(STOP) }
    }
}
