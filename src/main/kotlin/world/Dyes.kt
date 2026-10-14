package org.koremc.world

/**
 * The colour of each dye, as the game colours things with it, by the dye's
 * item: as red, green and blue, without alpha.
 */
internal val DYE_COLOURS: Map<String, Int> = mapOf(
    "white" to 0xF0F0F0, "orange" to 0xF9801D, "magenta" to 0xC74EBD, "light_blue" to 0x3AB3DA, "yellow" to 0xFED83D, "lime" to 0x80C71F,
    "pink" to 0xF38BAA, "gray" to 0x474F52, "light_gray" to 0x9D9D97, "cyan" to 0x169C9C, "purple" to 0x8932B8, "blue" to 0x3C44AA,
    "brown" to 0x835432, "green" to 0x5E7C16, "red" to 0xB02E26, "black" to 0x1D1D21,
).entries.associate { (dye, rgb) -> "minecraft:${dye}_dye" to rgb }

/** A colour as the game keeps one, opaque: alpha, red, green and blue, from [rgb]. */
internal fun opaque(rgb: Int): Int = 0xFF000000.toInt() or rgb
