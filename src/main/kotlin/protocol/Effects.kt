package org.koremc.protocol

import io.netty.buffer.ByteBuf

private const val MOB_EFFECT = 28

/**
 * An effect on the entity [runtimeId] starting, changing or ending, as
 * [event] says: the effect numbered [effect], [amplifier] levels above its
 * first, for [duration] ticks. The client counts the ticks down itself.
 * An [ambient] one, like a beacon's, shows fainter particles.
 */
data class MobEffect(
    val runtimeId: Long,
    val event: Int,
    val effect: Int,
    val amplifier: Int = 0,
    val duration: Int = 0,
    val ambient: Boolean = false,
    val particles: Boolean = true,
) : ServerPacket {
    override val id get() = MOB_EFFECT

    override fun encodeBody(out: ByteBuf) {
        out.writeVarULong(runtimeId)
        out.writeByte(event)
        out.writeVarInt(effect)
        out.writeVarInt(amplifier)
        out.writeBoolean(particles)
        out.writeVarInt(duration)
        out.writeVarULong(0) // The input tick it applies at; 0 for now
        out.writeBoolean(ambient)
    }

    companion object {
        const val ADD = 1
        const val MODIFY = 2
        const val REMOVE = 3
    }
}
