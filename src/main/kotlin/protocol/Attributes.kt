package org.koremc.protocol

import io.netty.buffer.ByteBuf

private const val UPDATE_ATTRIBUTES = 29

/** One of an entity's attributes, like `minecraft:health` or `minecraft:movement`, with its range and default. */
data class Attribute(
    val name: String,
    val min: Float,
    val max: Float,
    val value: Float,
    val default: Float = value,
)

/** Sets attributes on an entity. Like [SetActorData], a nonzero [tick] applies them at that input tick. */
data class UpdateAttributes(val runtimeId: Long, val attributes: List<Attribute>, val tick: Long = 0) : ServerPacket {
    override val id get() = UPDATE_ATTRIBUTES

    override fun encodeBody(out: ByteBuf) {
        out.writeVarULong(runtimeId)
        out.writeVarUInt(attributes.size)
        for (attribute in attributes) {
            out.writeFloatLE(attribute.min)
            out.writeFloatLE(attribute.max)
            out.writeFloatLE(attribute.value)
            out.writeFloatLE(attribute.min) // The default range, the same as the range
            out.writeFloatLE(attribute.max)
            out.writeFloatLE(attribute.default)
            out.writeString(attribute.name)
            out.writeVarUInt(0) // Modifiers, like sprinting's speed boost
        }
        out.writeVarULong(tick)
    }
}
