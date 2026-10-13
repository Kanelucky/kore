package org.koremc.world

import org.koremc.registry.BlockState

/** The strongest redstone power, which a source gives, and dust carries a step less of each block from it. */
const val MAX_POWER = 15

internal const val REDSTONE_WIRE = "minecraft:redstone_wire"
internal const val REDSTONE_SIGNAL = "redstone_signal"
internal const val REDSTONE_TORCH = "minecraft:redstone_torch"
internal const val UNLIT_REDSTONE_TORCH = "minecraft:unlit_redstone_torch"
private const val REDSTONE_BLOCK = "minecraft:redstone_block"

/** How long a redstone torch takes to answer the block it's on being powered, or not any more, in ticks. */
private const val TORCH_DELAY = 2

/**
 * A redstone torch put out this many times within [TOGGLE_MEMORY] ticks
 * burns out, and looks again [BURNOUT_TICKS] later, staying out until
 * those times are older than that.
 */
private const val BURNOUT_TOGGLES = 8
private const val TOGGLE_MEMORY = 60
private const val BURNOUT_TICKS = 160

/** The sound of a redstone torch burning out. */
private const val FIZZ = "fizz"

/**
 * How far from a block that changed redstone looks for what the change
 * may have powered, or stopped powering: two steps, from a lever to beside
 * the block it's fixed to.
 */
private const val POWER_REACH = 2

/** How much dust redstone follows from one change, at most, so that a vast spread of it can't hold the world up. */
private const val MAX_WIRE = 4096

/** How many times, at most, redstone goes round what changed in one tick, as one change leads to another. */
private const val MAX_PASSES = 16

/** Blocks besides dust that dust beside them points into: sources of power, and parts of redstone that carry it on. */
private val WIRE_JOINS = setOf(
    "lever", "redstone_torch", "unlit_redstone_torch", "redstone_block", "daylight_detector", "daylight_detector_inverted",
    "tripwire_hook", "trapped_chest", "target", "detector_rail", "lectern", "lightning_rod",
)
private val WIRE_JOIN_ENDINGS = listOf("button", "pressure_plate")

internal val BlockState.isWire get() = name == REDSTONE_WIRE

private val BlockState.isRedstoneTorch get() = name == REDSTONE_TORCH || name == UNLIT_REDSTONE_TORCH

/**
 * Whether power passes through this block to what's around it: a whole
 * one, though not glass, leaves or ice, nor a redstone block, which gives
 * power of its own instead.
 */
internal val BlockState.conductsPower get() = isFullCube && !letsLightThrough && name != REDSTONE_BLOCK

/** Whether the block at [pos] is powered: something beside it gives it any power. */
fun FlatWorld.isPowered(pos: BlockPos): Boolean = Face.entries.any { signal(pos.side(it), it.opposite) > 0 }

/**
 * The power the block at [from] gives the block beside it on its
 * [towards] side, from 0 to 15. A source gives what it gives, and a block
 * power passes through gives the strongest power going straight into it.
 * Dust's power counts only [withWire]: dust takes none from dust through a
 * block, so a block that only dust powers powers no other dust.
 */
internal fun FlatWorld.signal(from: BlockPos, towards: Face, withWire: Boolean = true): Int {
    val state = stateAt(from) ?: return 0
    if (state.conductsPower) return strongestInto(from, withWire)
    return when {
        state.givesPower || state.name == REDSTONE_BLOCK -> MAX_POWER
        state.name == REDSTONE_TORCH -> if (towards == state.torchHeldFrom) 0 else MAX_POWER
        state.isWire && withWire -> wireGives(from, state, towards)
        else -> 0
    }
}

/**
 * The strongest power going straight into the block at [pos], which it
 * passes on to what's beside it: from a lever or button fixed to it, a lit
 * redstone torch under it, or, [withWire], dust on it or pointing into it.
 */
private fun FlatWorld.strongestInto(pos: BlockPos, withWire: Boolean): Int = Face.entries.maxOf { face ->
    val from = pos.side(face)
    val towards = face.opposite
    val state = stateAt(from)
    when {
        state == null -> 0
        state.givesPower -> if (state.heldFrom == towards) MAX_POWER else 0
        state.name == REDSTONE_TORCH -> if (towards == Face.UP) MAX_POWER else 0
        state.isWire && withWire -> wireGives(from, state, towards)
        else -> 0
    }
}

/** Dust's power into the block on its [towards] side: none up, all of it down into the block it's on, and sideways where it points. */
private fun FlatWorld.wireGives(pos: BlockPos, state: BlockState, towards: Face): Int {
    val power = state.properties[REDSTONE_SIGNAL] as? Int ?: 0
    return when {
        power == 0 || towards == Face.UP -> 0
        towards == Face.DOWN || towards in wirePoints(pos) -> power
        else -> 0
    }
}

/**
 * The sides dust at [pos] points to: those with dust it runs to, or a
 * source of power. Dust that points one way runs on across the other way
 * too, and dust with nothing around it points every way.
 */
internal fun FlatWorld.wirePoints(pos: BlockPos): List<Face> {
    val joined = AROUND.filter { side -> wireBeside(pos, side) != null || stateAt(pos.side(side))?.joinsWire(side) == true }
    return when (joined.size) {
        0 -> AROUND
        1 -> joined + joined[0].opposite
        else -> joined
    }
}

/**
 * The dust the dust at [pos] runs to on its [side], if any: right beside
 * it, a step up, on the block beside it, or a step down, beside that
 * block. A block power passes through cuts a step where it's on the lower
 * dust. As in Bedrock, power goes down a step as well as up it, even from
 * the top of glass.
 */
private fun FlatWorld.wireBeside(pos: BlockPos, side: Face): BlockPos? {
    val beside = pos.side(side)
    val state = stateAt(beside) ?: return null
    if (state.isWire) return beside
    val up = beside.side(Face.UP)
    if (stateAt(up)?.isWire == true && stateAt(pos.side(Face.UP))?.conductsPower != true) return up
    val down = beside.side(Face.DOWN)
    if (stateAt(down)?.isWire == true && !state.conductsPower) return down
    return null
}

/**
 * Whether dust beside this block, on its [side], points into it: a
 * source of power, or a repeater or comparator facing along the way to
 * the dust. Dust doesn't point into what power works, like doors.
 */
private fun BlockState.joinsWire(side: Face): Boolean {
    val name = name.removePrefix("minecraft:")
    return when {
        name in WIRE_JOINS || WIRE_JOIN_ENDINGS.any(name::endsWith) -> true
        name.endsWith("repeater") || name.endsWith("comparator") -> (properties[DIRECTION] as? Int)?.let(Face::ofLegacyIndex)?.axis == side.axis
        else -> false
    }
}

/** The side of a redstone torch the block it's on is: below it, unless it's on a wall. */
private val BlockState.torchHeldFrom: Face get() = heldFrom ?: Face.DOWN

/**
 * Brings what redstone powers in line with the blocks that changed since
 * it last looked, and those around them, then with what that changed, and
 * so on. Dust takes the power that reaches it first. A redstone torch
 * looks again two ticks later, once the block it's on has started or
 * stopped being powered. A dispenser or dropper that power has just
 * reached fires a moment later, and a hopper is locked for as long as
 * it's powered. A door, trapdoor or fence gate opens as power reaches it,
 * and shuts as the power goes, a door if either half is powered. Between
 * times, a player can open or shut a wooden one by hand, and it stays so
 * until the power changes. Returns where blocks changed, and the sounds
 * they made.
 */
fun FlatWorld.updatePower(): Changes {
    val changed = LinkedHashSet<BlockPos>()
    val sounds = mutableListOf<BlockSound>()
    var passes = 0
    while (powerChanges.isNotEmpty() && passes++ < MAX_PASSES) {
        val near = powerChanges.flatMap { it.around(POWER_REACH) }.filterTo(LinkedHashSet()) { it.y in MIN_Y..MAX_Y }
        powerChanges.clear()
        changed += updateWire(near)
        for (pos in near) answerPower(pos, changed, sounds)
    }
    return Changes(emptyMap(), changed.toList(), sounds)
}

/**
 * Works out the power of the dust at any of [around], and all the dust it
 * runs to, as redstone carries it: each takes the strongest power given it
 * by anything but dust, or one less than the dust it runs to has, if
 * that's more. Returns the dust whose power changed.
 */
private fun FlatWorld.updateWire(around: Collection<BlockPos>): List<BlockPos> {
    val wire = LinkedHashSet<BlockPos>()
    val queue = ArrayDeque(around.filter { stateAt(it)?.isWire == true })
    while (queue.isNotEmpty() && wire.size < MAX_WIRE) {
        val pos = queue.removeFirst()
        if (wire.add(pos)) AROUND.mapNotNullTo(queue) { wireBeside(pos, it) }
    }
    val power = wire.associateWithTo(HashMap()) { pos -> Face.entries.maxOf { signal(pos.side(it), it.opposite, withWire = false) } }
    // From the strongest down, each dust lends the dust it runs to one less than it has.
    val byPower = Array(MAX_POWER + 1) { ArrayDeque<BlockPos>() }
    for ((pos, level) in power) byPower[level] += pos
    for (level in MAX_POWER downTo 2) {
        while (byPower[level].isNotEmpty()) {
            val pos = byPower[level].removeFirst()
            if (power[pos] != level) continue
            for (next in AROUND.mapNotNull { wireBeside(pos, it) }) {
                val now = power[next] ?: continue
                if (now >= level - 1) continue
                power[next] = level - 1
                byPower[level - 1] += next
            }
        }
    }
    val changed = mutableListOf<BlockPos>()
    for ((pos, level) in power) {
        val state = stateAt(pos) ?: continue
        if (state.properties[REDSTONE_SIGNAL] == level) continue
        blocks.find(state.name, state.properties + (REDSTONE_SIGNAL to level))?.let { set(pos, it); changed += pos }
    }
    return changed
}

/** Brings the block at [pos] in line with the power around it, if it answers power. */
private fun FlatWorld.answerPower(pos: BlockPos, changed: MutableSet<BlockPos>, sounds: MutableList<BlockSound>) {
    val state = stateAt(pos) ?: return
    when {
        state.opensWithPower -> {
            // A door goes by its lower half, which keeps both halves' power.
            val lower = if (state.properties[UPPER_BLOCK] == 1.toByte()) pos.side(Face.DOWN) else pos
            val powered = isPowered(lower) || (UPPER_BLOCK in state.properties && isPowered(lower.side(Face.UP)))
            if (powered == lower in poweredOpen) return
            if (powered) poweredOpen += lower else poweredOpen -= lower
            val swung = stateAt(lower)?.let { swingByPower(lower, it, powered) } ?: return
            changed += swung.changed
            swung.sound?.let { sounds += BlockSound(lower, it, swung.soundData) }
        }
        state.isRedstoneTorch -> {
            if (torchLit(pos, state) == (state.name == REDSTONE_TORCH) || !torchesDue.add(pos)) return
            schedule(pos, TORCH_DELAY, BlockUpdate.TORCH)
        }
        state.name == HOPPER || state.dispenserKind != null -> {
            val property = if (state.name == HOPPER) HOPPER_LOCKED else TRIGGERED
            val powered = isPowered(pos)
            if ((state.properties[property] == 1.toByte()) == powered) return
            blocks.find(state.name, state.properties + (property to bit(powered)))?.let { set(pos, it); changed += pos }
            if (powered) dispenserAt(pos)?.fireIn = DISPENSE_DELAY
        }
    }
}

/** Whether the redstone torch at [pos] is to be lit: while nothing powers the block it's on. */
private fun FlatWorld.torchLit(pos: BlockPos, state: BlockState): Boolean {
    val on = state.torchHeldFrom
    return signal(pos.side(on), on.opposite) == 0
}

/**
 * Lights the redstone torch at [pos], or puts it out, if the block it's on
 * has stopped or started being powered, as it looks again. A torch put out
 * eight times in three seconds burns out, with a fizz, and looks again in
 * eight seconds, when it lights if it's free to. Until then, it stays out.
 * Returns whether it changed.
 */
internal fun FlatWorld.tickTorch(pos: BlockPos, state: BlockState, sounds: MutableList<BlockSound>): Boolean {
    torchesDue -= pos
    if (!state.isRedstoneTorch) return false
    val toggles = torchToggles.getOrPut(pos) { mutableListOf() }.apply { removeAll { blockTicks - it > TOGGLE_MEMORY } }
    val lit = state.name == REDSTONE_TORCH
    val lighting = torchLit(pos, state)
    if (lit == lighting || (lighting && toggles.size >= BURNOUT_TOGGLES)) return false
    blocks.find(if (lighting) REDSTONE_TORCH else UNLIT_REDSTONE_TORCH, state.properties)?.let { set(pos, it) } ?: return false
    if (!lighting) {
        toggles += blockTicks
        if (toggles.size >= BURNOUT_TOGGLES) {
            sounds += BlockSound(pos, FIZZ, -1)
            torchesDue += pos
            schedule(pos, BURNOUT_TICKS, BlockUpdate.TORCH)
        }
    }
    return true
}

/** The blocks no more than [reach] steps from this one, along the axes, this one among them. */
private fun BlockPos.around(reach: Int): List<BlockPos> {
    val around = mutableListOf<BlockPos>()
    for (dx in -reach..reach) for (dy in -reach..reach) for (dz in -reach..reach) {
        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= reach) around += BlockPos(x + dx, y + dy, z + dz)
    }
    return around
}
