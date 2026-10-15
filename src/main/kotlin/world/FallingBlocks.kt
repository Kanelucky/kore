package org.koremc.world

import org.koremc.protocol.LevelEvent
import org.koremc.registry.AIR
import org.koremc.registry.BlockState
import kotlin.random.Random

/** How long after a block has nothing under it, or nothing holding it up, it starts to fall, in ticks. */
private const val FALL_DELAY = 2

// How falling blocks move each tick, as in the game: they fall faster, and slow in the air.
private const val GRAVITY = 0.04
private const val DRAG = 0.98

/** A falling block's width and height, a hair under a whole block's, so it slips down a hole one block wide. */
internal const val FALLING_BLOCK_SIZE = 0.98f

/** How long a block can fall without landing before it drops as its item: 30 seconds. */
private const val FALL_LIFETIME = 600

/** How far below the world a falling block falls before it's gone. */
private const val VOID_DEPTH = 64

/**
 * How far into the block a falling block's bottom is in it counts, so a
 * block resting a hair above another, as floats leave it, lands on that,
 * not in the one below.
 */
private const val LANDING_SLACK = 1e-3f

/** The chance an anvil wears on landing for each block it fell, once it's fallen more than one. */
private const val ANVIL_WEAR_PER_BLOCK = 0.05f

/** The sounds of a pointed dripstone breaking as it lands, and of suspicious sand or gravel crumbling, each its block's own. */
private const val LAND = "land"
private const val BREAK = "break"

internal const val POINTED_DRIPSTONE = "minecraft:pointed_dripstone"
private const val SULFUR_SPIKE = "minecraft:sulfur_spike"

/** Blocks that fall when there's nothing under them, besides concrete powder of every colour. */
private val FALLING = setOf(
    "minecraft:sand", "minecraft:red_sand", "minecraft:gravel", "minecraft:dragon_egg",
    "minecraft:anvil", "minecraft:chipped_anvil", "minecraft:damaged_anvil", "minecraft:deprecated_anvil",
)

/** Suspicious sand and gravel, which fall unless they hang from the block above, and crumble when they land. */
private val SUSPICIOUS = setOf("minecraft:suspicious_sand", "minecraft:suspicious_gravel")

/** What an anvil wears to as it lands, a stage at a time: a damaged one breaks. */
private val ANVIL_WEAR = mapOf(
    "minecraft:anvil" to "minecraft:chipped_anvil",
    "minecraft:chipped_anvil" to "minecraft:damaged_anvil",
    "minecraft:damaged_anvil" to null,
)

/** Pointed dripstone and sulfur spikes, which grow from the block above or below them. */
internal val BlockState.isDripstone get() = name == POINTED_DRIPSTONE || name == SULFUR_SPIKE

/** Whether this block falls when there's nothing under it, as sand does. */
internal val BlockState.fallsFreely: Boolean
    get() = name in FALLING || name.endsWith("concrete_powder") || (name in SUSPICIOUS && properties[HANGING] != 1.toByte())

/**
 * Whether this block falls, rather than breaks, when what holds it up
 * goes: snow, as in Bedrock, and pointed dripstone hanging from above.
 */
internal val BlockState.fallsUnheld: Boolean get() = name == SNOW_LAYER || (isDripstone && properties[HANGING] == 1.toByte())

/** Whether a falling block falls through [state], or lands in its place: air, fluids, fire, and the like a placed block replaces. */
private fun fallsThrough(state: BlockState?) = state == null || state.replaceable

/** Whether [state], at [pos], falls now: it's one that falls freely, with nothing under it. */
internal fun FlatWorld.canFall(pos: BlockPos, state: BlockState) = state.fallsFreely && fallsThrough(stateAt(pos.side(Face.DOWN)))

/**
 * A block falling, as an entity, the game's falling block: [state], from
 * the height [fromY]. [position] is the middle of its bottom, and
 * [velocity] is in blocks a tick, straight down.
 */
class FallingBlock(val id: Long, val state: BlockState, position: Vec3, internal val fromY: Int, velocity: Vec3 = Vec3(0f, 0f, 0f)) {
    var position = position
        private set
    var velocity = velocity
        private set

    /** Whether it moved in the last tick, so those who see it need to hear where it is now. */
    var moved = false
        private set

    /** Whether a command moved it since it last fell. */
    private var teleported = false

    /** How long it's been falling, in ticks. */
    internal var age = 0

    /** Moves it down a tick, as far as the blocks under it let it. Returns whether it's landed. */
    internal fun fall(world: FlatWorld): Boolean {
        age++
        val vy = velocity.y - GRAVITY
        val box = Box.of(position, FALLING_BLOCK_SIZE, FALLING_BLOCK_SIZE)
        val dy = world.clip(box, Axis.Y, vy)
        val start = position
        position = box.shifted(Axis.Y, dy).bottom()
        moved = teleported || position != start
        teleported = false
        val landed = dy != vy
        velocity = Vec3(0f, if (landed) 0f else (vy * DRAG).toFloat(), 0f)
        return landed
    }

    /** Puts it [at] a point, stopped, to fall on from there, as a command does. */
    fun teleport(at: Vec3) {
        position = at
        velocity = Vec3(0f, 0f, 0f)
        teleported = true
    }
}

/**
 * Looks at the blocks that [changed], and those above them, for any that
 * now have nothing under them and fall, and sets those falling a moment
 * later. Run once a tick, before blocks are ticked.
 */
internal fun FlatWorld.lookForFalls(changed: Collection<BlockPos>) {
    for (pos in changed.flatMap { listOf(it, it.side(Face.UP)) }.distinct()) {
        if (pos.y !in MIN_Y..MAX_Y) continue
        val state = stateAt(pos) ?: continue
        if (canFall(pos, state)) schedule(pos, FALL_DELAY, BlockUpdate.FALL)
    }
}

/** Sets the block at [pos], [state], falling, leaving air where it was. */
internal fun FlatWorld.startFalling(pos: BlockPos, state: BlockState): FallingBlock {
    set(pos, blocks.stateOf(AIR))
    return spawnFallingBlock(state, Vec3(pos.x + 0.5f, pos.y.toFloat(), pos.z + 0.5f), pos.y)
}

/**
 * Sets the block at [pos], [state], which has lost what held it up,
 * falling: snow by itself, and pointed dripstone with the rest of it
 * hanging below, down to its tip. Returns where blocks fell from.
 */
internal fun FlatWorld.fallFrom(pos: BlockPos, state: BlockState): List<BlockPos> {
    if (!state.isDripstone) return listOf(pos).also { startFalling(pos, state) }
    val fell = mutableListOf<BlockPos>()
    var at = pos
    while (true) {
        val hanging = stateAt(at)?.takeIf { it.name == state.name && it.properties[HANGING] == 1.toByte() } ?: break
        startFalling(at, hanging)
        fell += at
        at = at.side(Face.DOWN)
    }
    return fell
}

/**
 * Moves every falling block down a tick. One that lands takes its place
 * where it came down, if the block there is one a placed block replaces,
 * the block under it isn't, and it can stand there. An anvil may wear as
 * it lands, the further it fell the likelier, or break, and clangs if it
 * doesn't. One that can't take its place drops as its item, in [dropped],
 * but for suspicious sand and gravel, which crumble wherever they land,
 * and pointed dripstone breaks with its sound. One that falls for long
 * enough without landing drops too, and one that falls out of the world
 * is gone. Concrete powder falling into water stops there, as concrete.
 * Their sounds go in [sounds] and [events]. Returns where blocks landed.
 */
internal fun FlatWorld.moveFallingBlocks(sounds: MutableList<BlockSound>, events: MutableList<WorldEvent>, dropped: MutableList<Pair<Vec3, BlockState>>): List<BlockPos> {
    val landed = mutableListOf<BlockPos>()
    for (falling in fallingBlocks.toList()) {
        val fell = falling.fall(this)
        // Concrete powder falling into water hardens there.
        val wetAt = Vec3(falling.position.x, falling.position.y + FALLING_BLOCK_SIZE / 2, falling.position.z).toBlockPos()
        val concrete = falling.state.name.takeIf { it.endsWith("_concrete_powder") && isWet(wetAt) }?.let { blocks.defaultOf(it.removeSuffix("_powder")) }
        if (concrete != null && wetAt.y in MIN_Y..MAX_Y) {
            removeFallingBlock(falling.id)
            set(wetAt, concrete)
            landed += wetAt
            continue
        }
        if (!fell) {
            if (falling.position.y < MIN_Y - VOID_DEPTH) {
                removeFallingBlock(falling.id)
            } else if (falling.age >= FALL_LIFETIME) {
                removeFallingBlock(falling.id)
                dropped += falling.position to falling.state
            }
            continue
        }
        removeFallingBlock(falling.id)
        val state = falling.state
        val at = Vec3(falling.position.x, falling.position.y + LANDING_SLACK, falling.position.z).toBlockPos()
        if (state.name in SUSPICIOUS) {
            sounds += BlockSound(at, BREAK, state.networkId)
            continue
        }
        val placeable = stateAt(at)?.replaceable == true && !fallsThrough(stateAt(at.side(Face.DOWN))) && at.y in MIN_Y..MAX_Y && survives(at, state)
        if (!placeable) {
            if (state.isDripstone) sounds += BlockSound(at, LAND, state.networkId)
            dropped += falling.position to state
            continue
        }
        val fallen = falling.fromY - at.y
        val worn = state.name in ANVIL_WEAR && fallen > 1 && Random.nextFloat() < ANVIL_WEAR_PER_BLOCK * fallen
        if (worn && ANVIL_WEAR[state.name] == null) {
            events += WorldEvent(at, LevelEvent.SOUND_ANVIL_BREAK, 0)
            continue
        }
        val lands = if (worn) blocks.find(ANVIL_WEAR.getValue(state.name)!!, state.properties) ?: state else state
        set(at, lands)
        landed += at
        if (state.name.endsWith("anvil")) events += WorldEvent(at, LevelEvent.SOUND_ANVIL_LAND, 0)
    }
    return landed
}
