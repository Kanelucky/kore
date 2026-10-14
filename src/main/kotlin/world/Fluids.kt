package org.koremc.world

import org.koremc.registry.AIR
import org.koremc.registry.BlockState
import org.koremc.registry.LiquidReaction
import kotlin.random.Random

internal const val WATER = "minecraft:water"
internal const val FLOWING_WATER = "minecraft:flowing_water"
internal const val LAVA = "minecraft:lava"
internal const val FLOWING_LAVA = "minecraft:flowing_lava"

/** How deep a liquid is, as the game numbers it: 0 for a source, more the further it's flowed from one, and 8 and up for liquid falling. */
private const val DEPTH = "liquid_depth"
private const val FALLING = 8

/** The depth from which liquid is falling, as items in it find it. */
internal const val FALLING_DEPTH = FALLING

/** Liquid no route down is found for, as far as it looks. */
private const val NO_WAY_DOWN = 1000

/** The sound of lava meeting water, or burning away what it flows into. */
private const val FIZZ = "fizz"

/**
 * The two liquids, as they behave in the overworld: water flows seven
 * blocks from its source, looks four blocks off for a way down, and moves
 * every five ticks; lava flows three, looks two off, and moves every
 * thirty, thinning twice as fast.
 */
enum class Liquid(val still: String, val flowing: String, internal val delay: Int, internal val thinning: Int, internal val reach: Int) {
    WATER(org.koremc.world.WATER, FLOWING_WATER, delay = 5, thinning = 1, reach = 4),
    LAVA(org.koremc.world.LAVA, FLOWING_LAVA, delay = 30, thinning = 2, reach = 2),
}

/** The liquid this block is, or null for one that isn't. */
val BlockState.liquid: Liquid?
    get() = when (name) {
        WATER, FLOWING_WATER -> Liquid.WATER
        LAVA, FLOWING_LAVA -> Liquid.LAVA
        else -> null
    }

/** How deep this liquid is: 0 for a source. */
internal val BlockState.liquidDepth: Int get() = properties[DEPTH] as? Int ?: 0

/** The liquid at a block, how deep, and whether it's [inside] the block there, as water in a fence is, rather than the block itself. */
internal data class LiquidHere(val liquid: Liquid, val depth: Int, val inside: Boolean)

/** The liquid at [pos], whether it's the block there or the water inside it, or null for none. */
internal fun FlatWorld.liquidHere(pos: BlockPos): LiquidHere? {
    stateAt(pos)?.let { block -> block.liquid?.let { return LiquidHere(it, block.liquidDepth, inside = false) } }
    liquidAt(pos)?.let { inside -> inside.liquid?.let { return LiquidHere(it, inside.liquidDepth, inside = true) } }
    return null
}

/** How deep [liquid] is at [pos], or -1 if it isn't there. */
private fun FlatWorld.depthOf(pos: BlockPos, liquid: Liquid) = liquidHere(pos)?.takeIf { it.liquid == liquid }?.depth ?: -1

/** Whether there's water at [pos], as the block there or inside it. */
internal fun FlatWorld.isWet(pos: BlockPos) = liquidHere(pos)?.liquid == Liquid.WATER

/** [liquid], [depth] deep, as a block: still as a source inside another block, as the game keeps water in a fence, and flowing otherwise, as the game makes it. */
internal fun FlatWorld.liquidState(liquid: Liquid, depth: Int, inside: Boolean = false): BlockState =
    blocks.find(if (inside && depth == 0) liquid.still else liquid.flowing, mapOf(DEPTH to depth)) ?: blocks.stateOf(AIR)

/**
 * What [state] does when liquid flows into it, as the game says: air and
 * liquid let it in, and without the game's word, as in tests, what a
 * placed block replaces gives way to it, and anything else stops it.
 */
internal fun FlatWorld.reactionTo(state: BlockState): LiquidReaction = when {
    state.name == AIR || state.liquid != null -> LiquidReaction.NO_REACTION
    state.networkId in traits -> traits[state.networkId].liquidReaction
    state.replaceable -> LiquidReaction.POPPED
    else -> LiquidReaction.BLOCKING
}

/** Whether water can be inside [state], as in a fence or stairs. */
internal fun FlatWorld.holdsWater(state: BlockState) = state.name != AIR && state.liquid == null && traits[state.networkId].holdsWater

/** Whether the block at [pos] stops [liquid] going into it: a liquid there doesn't, and neither does what gives way to it or lets it in. */
private fun FlatWorld.stops(pos: BlockPos, liquid: Liquid): Boolean {
    if (pos.y !in MIN_Y..MAX_Y) return pos.y > MAX_Y
    val state = stateAt(pos) ?: return true
    if (state.liquid != null) return false
    return when (reactionTo(state)) {
        LiquidReaction.NO_REACTION -> !(state.name == AIR || (liquid == Liquid.WATER && holdsWater(state)))
        LiquidReaction.POPPED, LiquidReaction.BROKEN -> false
        LiquidReaction.BLOCKING -> true
    }
}

/** Whether [liquid] can flow into [pos]: nothing there stops it, and there's no liquid there already. */
private fun FlatWorld.canFlowInto(pos: BlockPos, liquid: Liquid) = pos.y in MIN_Y..MAX_Y && !stops(pos, liquid) && liquidHere(pos) == null

/** Whether [liquid] at [pos] is due to move, so it isn't due twice at once. */
private fun FlatWorld.flowSoon(pos: BlockPos, liquid: Liquid) {
    if (flowDue.add(pos)) schedule(pos, liquid.delay, BlockUpdate.FLOW)
}

/**
 * Looks at the liquid at and around [changed], where blocks changed since
 * it last did: lava touching water hardens, as concrete powder does, at
 * once, and other liquid moves a moment later. Run once a tick, before
 * blocks are ticked. Where blocks hardened goes in the result, and their
 * fizz in [sounds].
 */
internal fun FlatWorld.lookForFlows(changed: Collection<BlockPos>, sounds: MutableList<BlockSound>): List<BlockPos> {
    val hardened = mutableListOf<BlockPos>()
    for (at in changed.flatMap { pos -> listOf(pos) + Face.entries.map(pos::side) }.distinct()) {
        if (at.y !in MIN_Y..MAX_Y) continue
        val state = stateAt(at) ?: continue
        if (hardens(at, state, sounds)) {
            hardened += at
            continue
        }
        liquidHere(at)?.let { flowSoon(at, it.liquid) }
    }
    return hardened
}

/**
 * Hardens [state] at [pos] if water touches it: lava, from a side or above,
 * into obsidian if it's a source and cobblestone if not, with a fizz, and
 * concrete powder into concrete. Returns whether it did.
 */
private fun FlatWorld.hardens(pos: BlockPos, state: BlockState, sounds: MutableList<BlockSound>): Boolean {
    val touched = (Face.entries - Face.DOWN).any { isWet(pos.side(it)) }
    if (!touched) return false
    val hard = when {
        state.liquid == Liquid.LAVA -> blocks.stateOf(if (state.liquidDepth == 0) "minecraft:obsidian" else "minecraft:cobblestone")
        state.name.endsWith("_concrete_powder") -> blocks.defaultOf(state.name.removeSuffix("_powder"))
        else -> null
    } ?: return false
    set(pos, hard)
    if (state.liquid == Liquid.LAVA) sounds += BlockSound(pos, FIZZ, -1)
    return true
}

/**
 * Moves the liquid at [pos], as the game does each time it's due. Liquid
 * that isn't a source takes its depth from the liquid beside it, one
 * step thinner, or from liquid above it, falling, and dries up with none
 * to feed it. Water between two sources, over a floor or a source, is a
 * source too. Then it flows down if it can, or out to the sides if it's
 * a source or has a floor, the ways nearest a drop, as far as it reaches.
 * Lava falling onto water turns it to stone. What it flows into breaks,
 * water's dropping as its item, in [broken], and lava burning it, with
 * the fizz in [sounds], unless it lets water in, as a lever does. Returns
 * where the liquid changed.
 */
internal fun FlatWorld.flow(pos: BlockPos, broken: MutableMap<BlockPos, BrokenBlock>, sounds: MutableList<BlockSound>): List<BlockPos> {
    val here = liquidHere(pos) ?: return emptyList()
    val liquid = here.liquid
    val changed = mutableListOf<BlockPos>()
    var depth = here.depth
    if (depth > 0) {
        var feeding = -1
        var sources = 0
        for (side in AROUND) {
            val beside = depthOf(pos.side(side), liquid).takeIf { it >= 0 } ?: continue
            if (beside == 0) sources++
            val level = if (beside >= FALLING) 0 else beside
            if (feeding < 0 || level < feeding) feeding = level
        }
        var next = if (feeding < 0 || feeding + liquid.thinning >= FALLING) -1 else feeding + liquid.thinning
        val above = depthOf(pos.side(Face.UP), liquid)
        if (above >= 0) next = if (above >= FALLING) above else above + FALLING
        if (sources >= 2 && liquid == Liquid.WATER) {
            val below = pos.side(Face.DOWN)
            if (stops(below, liquid) || depthOf(below, liquid) == 0) next = 0
        }
        // Lava thins slowly, and unevenly.
        if (liquid == Liquid.LAVA && depth < FALLING && next in depth + 1 until FALLING && Random.nextInt(4) != 0) {
            flowSoon(pos, liquid)
            next = depth
        }
        if (next != depth) {
            depth = next
            when {
                here.inside -> setLiquid(pos, if (depth < 0) null else liquidState(liquid, depth, inside = true))
                depth < 0 -> set(pos, blocks.stateOf(AIR))
                else -> set(pos, liquidState(liquid, depth))
            }
            changed += pos
            if (depth < 0) return changed
            flowSoon(pos, liquid)
        }
    }

    val below = pos.side(Face.DOWN)
    if (liquid == Liquid.LAVA && stateAt(below)?.liquid == Liquid.WATER) {
        set(below, blocks.stateOf("minecraft:stone"))
        sounds += BlockSound(below, FIZZ, -1)
        changed += below
    } else if (canFlowInto(below, liquid)) {
        flowInto(below, liquid, if (depth >= FALLING) depth else depth + FALLING, broken, sounds)?.let(changed::add)
    } else if (depth == 0 || stops(below, liquid)) {
        val onward = if (depth >= FALLING) liquid.thinning else depth + liquid.thinning
        if (onward < FALLING) {
            for (side in waysOut(pos, liquid)) flowInto(pos.side(side), liquid, onward, broken, sounds)?.let(changed::add)
        }
    }
    return changed
}

/**
 * Lets [liquid], [depth] deep, into [pos], if it can go there: what's
 * there breaks, water's dropping its item and lava burning it, or holds
 * the water inside it, if it lets water in. Returns where it went.
 */
private fun FlatWorld.flowInto(pos: BlockPos, liquid: Liquid, depth: Int, broken: MutableMap<BlockPos, BrokenBlock>, sounds: MutableList<BlockSound>): BlockPos? {
    if (!canFlowInto(pos, liquid)) return null
    val state = stateAt(pos)!!
    when (reactionTo(state)) {
        LiquidReaction.NO_REACTION -> if (state.name != AIR) {
            setLiquid(pos, liquidState(liquid, depth, inside = true))
            flowSoon(pos, liquid)
            return pos
        }
        LiquidReaction.POPPED -> if (liquid == Liquid.WATER) breakAt(pos, state, broken) else sounds += BlockSound(pos, FIZZ, -1)
        LiquidReaction.BROKEN, LiquidReaction.BLOCKING -> {}
    }
    set(pos, liquidState(liquid, depth))
    flowSoon(pos, liquid)
    return pos
}

/**
 * The sides [liquid] at [pos] flows out to: those nearest a way down, as
 * far as it looks, or, with none in reach, every side it can go.
 */
private fun FlatWorld.waysOut(pos: BlockPos, liquid: Liquid): List<Face> {
    val distances = AROUND.associateWith { side ->
        val next = pos.side(side)
        when {
            stops(next, liquid) || depthOf(next, liquid) == 0 -> null
            !stops(next.side(Face.DOWN), liquid) -> 0
            else -> wayDown(next, 1, side.opposite, liquid)
        }
    }
    val nearest = distances.values.filterNotNull().minOrNull() ?: return emptyList()
    return distances.filterValues { it == nearest }.keys.toList()
}

/** How many blocks on from [pos] the nearest way down for [liquid] is, not looking back the way it came, [from], or [NO_WAY_DOWN]. */
private fun FlatWorld.wayDown(pos: BlockPos, steps: Int, from: Face, liquid: Liquid): Int {
    var nearest = NO_WAY_DOWN
    for (side in AROUND) {
        if (side == from) continue
        val next = pos.side(side)
        if (stops(next, liquid) || depthOf(next, liquid) == 0) continue
        if (!stops(next.side(Face.DOWN), liquid)) return steps
        if (steps < liquid.reach) nearest = minOf(nearest, wayDown(next, steps + 1, side.opposite, liquid))
    }
    return nearest
}

/** Whether there's still water at [pos], or water falling down, which kelp and seagrass can go into. */
internal fun FlatWorld.isStillOrFallingWater(pos: BlockPos) = stateAt(pos)?.let { it.liquid == Liquid.WATER && (it.liquidDepth == 0 || it.liquidDepth >= FALLING) } == true
