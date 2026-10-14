package org.koremc.world

import org.koremc.registry.BlockState
import kotlin.math.floor

/** Stone pressure plates, which only players and mobs press. */
private val STONE_PLATES = setOf("minecraft:stone_pressure_plate", "minecraft:polished_blackstone_pressure_plate")

/** Weighted pressure plates, which give power by how much is on them. */
private const val LIGHT_WEIGHTED_PLATE = "minecraft:light_weighted_pressure_plate"
private const val HEAVY_WEIGHTED_PLATE = "minecraft:heavy_weighted_pressure_plate"

/**
 * How long a pressed plate waits to look again at what's on it, in ticks:
 * a second, or half that for a weighted one.
 */
private const val PLATE_TICKS = 20
private const val WEIGHTED_PLATE_TICKS = 10

/**
 * How high up its block a plate feels what's on it: a quarter of a block,
 * so it feels what flies just over it too. It stops a sixteenth short of
 * each side of its block, as it looks.
 */
private const val PLATE_REACH = 0.25
private const val PLATE_INSET = 1.0 / 16

/** How many things on a heavy weighted plate give it each step of power. */
private const val HEAVY_PLATE_STEP = 10

/** The clicks of a plate pressed down and coming back up. */
private const val CLICK_ON = "pressure_plate.click_on"
private const val CLICK_OFF = "pressure_plate.click_off"

internal val BlockState.isPressurePlate get() = name.endsWith("_pressure_plate")

private val BlockState.isWeightedPlate get() = name == LIGHT_WEIGHTED_PLATE || name == HEAVY_WEIGHTED_PLATE

/**
 * The power a pressure plate gives: as much as a weighted one's state
 * says, and all of it from any other that's pressed, whose state, as in
 * Bedrock, only says it's pressed.
 */
internal val BlockState.platePower: Int
    get() {
        if (!isPressurePlate) return 0
        val signal = properties[REDSTONE_SIGNAL] as? Int ?: 0
        return if (isWeightedPlate || signal == 0) signal else MAX_POWER
    }

/** This plate giving [power], which a plate that isn't weighted keeps as just being pressed. */
private fun FlatWorld.plateGiving(state: BlockState, power: Int): BlockState? {
    val signal = if (state.isWeightedPlate || power == 0) power else 1
    return blocks.find(state.name, state.properties + (REDSTONE_SIGNAL to signal))
}

/** What's on a pressure plate: how many things, and how many of them players. */
internal class PlateLoad {
    var things = 0
    var players = 0
}

/**
 * Presses the pressure plates that players standing at [players], by
 * their feet, and the world's items and orbs are on, those that aren't
 * pressed already: each gives power, by what's on it, with its click, and
 * looks again in a moment whether anything's still on it. Something's on
 * a plate whose box reaches into the bottom quarter of the plate's block,
 * as it would standing on the block below, which a plate doesn't hold up.
 * Wooden plates press under anything, stone ones only under players, and
 * weighted ones give a step of power for each thing on them, or each ten.
 * Returns where plates changed, and their clicks. With redstone switched
 * off, no plate presses.
 */
fun FlatWorld.pressPlates(players: Collection<Vec3>): Changes {
    if (!features.redstone) return Changes(emptyMap(), emptyList())
    val loads = HashMap<BlockPos, PlateLoad>()
    fun weigh(box: Box, player: Boolean) {
        for (x in floor(box.min[0]).toInt()..floor(box.max[0]).toInt()) for (z in floor(box.min[2]).toInt()..floor(box.max[2]).toInt()) {
            for (y in floor(box.min[1] - PLATE_REACH).toInt()..floor(box.max[1]).toInt()) {
                val pos = BlockPos(x, y, z)
                if (stateAt(pos)?.isPressurePlate != true || !plateArea(pos).overlaps(box)) continue
                val load = loads.getOrPut(pos) { PlateLoad() }
                load.things++
                if (player) load.players++
            }
        }
    }
    players.forEach { weigh(Box.player(it), player = true) }
    items.forEach { weigh(Box.around(it.position), player = false) }
    orbs.forEach { weigh(Box.around(it.position), player = false) }
    plateLoads = loads

    val pressed = mutableListOf<BlockPos>()
    val sounds = mutableListOf<BlockSound>()
    for ((pos, load) in loads) {
        val state = stateAt(pos) ?: continue
        val power = state.plateSignal(load)
        if (state.platePower > 0 || power == 0) continue
        plateGiving(state, power)?.let { set(pos, it) } ?: continue
        pressed += pos
        sounds += BlockSound(pos, CLICK_ON, blockAt(pos))
        // Plates press before the block tick they belong to begins, so the wait counts from that one.
        schedule(pos, state.plateTicks + 1, BlockUpdate.PLATE)
    }
    return Changes(emptyMap(), pressed, sounds)
}

/**
 * Looks again at what's on the pressed plate at [pos], [state], as it does
 * a moment after it's pressed, and every moment it stays pressed: it gives
 * power by what's on it now, and looks again in a moment, or comes back
 * up, with its click, once nothing that presses it is. Its click goes in
 * [sounds]. Returns whether it changed.
 */
internal fun FlatWorld.checkPlate(pos: BlockPos, state: BlockState, sounds: MutableList<BlockSound>): Boolean {
    if (!state.isPressurePlate || state.platePower == 0) return false
    val power = state.plateSignal(plateLoads[pos])
    if (power > 0) schedule(pos, state.plateTicks, BlockUpdate.PLATE)
    if (power == state.platePower) return false
    plateGiving(state, power)?.let { set(pos, it) } ?: return false
    if (power == 0) sounds += BlockSound(pos, CLICK_OFF, blockAt(pos))
    return true
}

/** Where the plate at [pos] feels what's on it. */
private fun plateArea(pos: BlockPos) = Box(
    doubleArrayOf(pos.x + PLATE_INSET, pos.y.toDouble(), pos.z + PLATE_INSET),
    doubleArrayOf(pos.x + 1 - PLATE_INSET, pos.y + PLATE_REACH, pos.z + 1 - PLATE_INSET),
)

/** The power this plate gives with [load] on it. */
private fun BlockState.plateSignal(load: PlateLoad?): Int {
    if (load == null) return 0
    return when (name) {
        LIGHT_WEIGHTED_PLATE -> minOf(load.things, MAX_POWER)
        HEAVY_WEIGHTED_PLATE -> minOf((load.things + HEAVY_PLATE_STEP - 1) / HEAVY_PLATE_STEP, MAX_POWER)
        in STONE_PLATES -> if (load.players > 0) MAX_POWER else 0
        else -> if (load.things > 0) MAX_POWER else 0
    }
}

/** How long this plate waits, pressed, to look again at what's on it. */
private val BlockState.plateTicks get() = if (isWeightedPlate) WEIGHTED_PLATE_TICKS else PLATE_TICKS
