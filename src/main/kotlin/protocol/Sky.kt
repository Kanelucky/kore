package org.koremc.protocol

import io.netty.buffer.ByteBuf

private const val SET_TIME = 10
private const val GAME_RULES_CHANGED = 72

// The kinds of game rule, as the game numbers them.
private const val ON_OR_OFF = 1
private const val NUMBER = 2

/** The world's time, in ticks since it began, which the client runs on from, a day being 24000 ticks. */
data class SetTime(val time: Long) : ServerPacket {
    override val id get() = SET_TIME

    override fun encodeBody(out: ByteBuf) {
        out.writeVarInt(time.toInt())
    }
}

/** The game rules that changed, by the names the game keeps them under, each on or off, or a number. */
data class GameRulesChanged(val rules: Map<String, Any>) : ServerPacket {
    override val id get() = GAME_RULES_CHANGED

    override fun encodeBody(out: ByteBuf) {
        out.writeVarUInt(rules.size)
        for ((name, value) in rules) out.writeGameRule(name, value)
    }
}

/**
 * Writes the game rule [name], [value], which players can't change from
 * their own settings: on or off as a byte, or a number as a little-endian
 * int, in `StartGame` as in [GameRulesChanged], as Mojang's protocol
 * documents it.
 */
internal fun ByteBuf.writeGameRule(name: String, value: Any) {
    writeString(name)
    writeBoolean(false) // Whether players can change it from their settings
    when (value) {
        is Boolean -> writeVarUInt(ON_OR_OFF).writeBoolean(value)
        is Int -> writeVarUInt(NUMBER).writeIntLE(value)
        else -> throw IllegalArgumentException("A game rule is on or off, or a number, not $value")
    }
}
