package org.koremc.world

import kotlinx.serialization.Serializable

/**
 * The game's systems Kore runs, each of which a server can switch off in
 * kore.json to go without it: all on, the game as it is, and all off, blocks
 * that are only blocks. A system switched off doesn't run at all, and the
 * blocks that are part of it do nothing. What the game's own rules already
 * switch off, like the weather or blocks' drops, the game rules do.
 */
@Serializable
data class Features(
    /**
     * Power, and all it works: dust, torches, repeaters, comparators,
     * observers, lamps, pistons, pressure plates and daylight detectors, and
     * the doors, note blocks, dispensers and hoppers it moves. Off, levers and
     * buttons still flip, but power goes nowhere.
     */
    val redstone: Boolean = true,
    /** Water and lava flowing, what they make where they meet, and concrete powder hardening in water. Off, they stay as they're put. */
    val liquids: Boolean = true,
    /** Sand, gravel, anvils and the like falling with nothing under them. Off, they stay where they are, and hanging dripstone and snow break instead. */
    val fallingBlocks: Boolean = true,
    /** Random ticks, bone meal, trampling farmland, and snow and ice forming: whatever grows, spreads, dries, decays, melts, freezes or weathers. */
    val growth: Boolean = true,
    /** Furnaces, brewing stands, hoppers, dispensers, droppers, crafters and beacons working. Off, they keep what's put in them, and do nothing with it. */
    val machines: Boolean = true,
) {
    /** The names of the systems switched off, as kore.json names them. */
    val off: List<String>
        get() = listOfNotNull(
            "redstone".takeUnless { redstone }, "liquids".takeUnless { liquids }, "fallingBlocks".takeUnless { fallingBlocks },
            "growth".takeUnless { growth }, "machines".takeUnless { machines },
        )
}
