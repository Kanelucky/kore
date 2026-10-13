package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.registry.AIR
import org.koremc.registry.BlockState

internal const val PISTON = "minecraft:piston"
internal const val STICKY_PISTON = "minecraft:sticky_piston"
private const val PISTON_HEAD = "minecraft:piston_arm_collision"
private const val STICKY_PISTON_HEAD = "minecraft:sticky_piston_arm_collision"
internal const val MOVING_BLOCK = "minecraft:moving_block"

/** How many blocks a piston moves at once, at most. */
private const val PUSH_LIMIT = 12

/** How long a piston waits to move once power reaches it, or goes, in ticks. */
internal const val PISTON_DELAY = 2

/** How far a piston's arm moves in a tick, of the block it moves out. */
private const val ARM_STEP = 0.5f

/** The sounds of a piston pushing out and pulling back in. */
private const val PISTON_OUT = "piston.out"
private const val PISTON_IN = "piston.in"

/** Blocks a piston can't move, whatever's beside them. */
private val IMMOVABLE = setOf(
    "obsidian", "crying_obsidian", "respawn_anchor", "reinforced_deepslate", "bedrock", "barrier", "end_portal_frame",
    "end_portal", "end_gateway", "portal", "command_block", "repeating_command_block", "chain_command_block",
    "structure_block", "structure_void", "jigsaw", "allow", "deny", "border_block", "moving_block",
    "piston_arm_collision", "sticky_piston_arm_collision",
    // Blocks that keep data of their own, which Kore doesn't carry along yet
    "enchanting_table", "beacon", "mob_spawner", "jukebox", "lectern", "conduit", "beehive", "bee_nest", "campfire",
    "soul_campfire", "chiseled_bookshelf", "crafter", "sculk_sensor", "calibrated_sculk_sensor", "sculk_shrieker",
    "sculk_catalyst", "trial_spawner", "vault", "daylight_detector", "daylight_detector_inverted", "noteblock",
).mapTo(HashSet()) { "minecraft:$it" }

/** Blocks a piston breaks, for what they drop, rather than moving them, besides those another block holds up. */
private val BROKEN_BY_PISTONS = setOf(
    "web", "dragon_egg", "pumpkin", "melon_block", "waterlily", "trip_wire", "chorus_plant", "chorus_flower", "vine",
    "glow_lichen", "sculk_vein", "frog_spawn", "decorated_pot", "bed",
).mapTo(HashSet()) { "minecraft:$it" }
private val BROKEN_BY_PISTONS_ENDINGS = listOf("_head", "_skull", "amethyst_bud", "amethyst_cluster")

private const val SLIME = "minecraft:slime"
private const val HONEY = "minecraft:honey_block"

val BlockState.isPiston get() = name == PISTON || name == STICKY_PISTON

private val BlockState.isPistonHead get() = name == PISTON_HEAD || name == STICKY_PISTON_HEAD

/** The way a piston pushes, which its facing direction stores the wrong way round when it's sideways. */
internal val BlockState.pistonFacing: Face
    get() = Face.of(properties[FACING_DIRECTION] as? Int ?: Face.UP.ordinal)?.let { if (it.horizontal) it.opposite else it } ?: Face.UP

/** Whether blocks beside this one move with it, as slime and honey drag them along. */
private val BlockState.isSticky get() = name == SLIME || name == HONEY

/**
 * A piston's arm, which the client sees as the piston's block entity, and
 * the blocks it's moving: how far out it is, from 0, in, to 1, out, and
 * how far it was a tick ago, which way it's going, where the blocks it
 * moves started, and what lands where once it's done.
 */
class PistonArm(val sticky: Boolean) {
    var progress = 0f
        internal set
    var lastProgress = 0f
        internal set
    var extending = false
        internal set
    var moving = false
        internal set

    /**
     * Whether power had reached the piston when it saw the power change,
     * which it moves by once it looks again, though a pulse, like an
     * observer's, may be over by then.
     */
    internal var pending: Boolean? = null
    internal val moved = mutableListOf<BlockPos>()
    internal val landing = LinkedHashMap<BlockPos, BlockState>()

    /** The state the client draws the arm in: in, moving out, out, or moving in. */
    private val state: Byte get() = (if (extending) if (moving) 1 else 2 else if (moving) 3 else 0).toByte()

    /** The arm as its block entity, as the client takes it: the blocks it's moving where they started, one coordinate after another. */
    fun data(): NbtMap = NbtMap.builder()
        .putString("id", "PistonArm")
        .putFloat("Progress", progress)
        .putFloat("LastProgress", lastProgress)
        .putByte("State", state)
        .putByte("NewState", state)
        .putBoolean("Sticky", sticky)
        .putBoolean("isMovable", !extending && !moving)
        .putIntArray("AttachedBlocks", moved.flatMap { listOf(it.x, it.y, it.z) }.toIntArray())
        .build()
}

/**
 * Whether the piston at [pos] is powered, from any side but its front,
 * by dust beside it too, whichever way that points, as in Bedrock.
 */
internal fun FlatWorld.pistonPowered(pos: BlockPos, state: BlockState): Boolean = Face.entries.any { side ->
    if (side == state.pistonFacing) return@any false
    val beside = stateAt(pos.side(side))
    signal(pos.side(side), side.opposite) > 0 || (beside?.isWire == true && (beside.properties[REDSTONE_SIGNAL] as? Int ?: 0) > 0)
}

/**
 * Pushes the piston at [pos] out, if power had reached it, or pulls it
 * back in, if the power had gone, when it looks again a moment after it
 * changed. Pushing out, it moves what's in front of it, up to twelve
 * blocks, and anything slime or honey drags along, and breaks what can't
 * be moved but breaks. Nothing moves, and it stays in, if something in the
 * way won't move. A sticky piston pulls back the block in front of its
 * arm, and whatever that drags along, unless it can't. The blocks it moves
 * are moving blocks until it's done, two ticks later. What broke is in
 * [broken], and where blocks changed in [reshaped].
 */
internal fun FlatWorld.movePiston(pos: BlockPos, state: BlockState, broken: MutableMap<BlockPos, BrokenBlock>, reshaped: MutableCollection<BlockPos>, sounds: MutableList<BlockSound>) {
    redstoneDue -= pos
    if (!state.isPiston) return
    val arm = pistonArm(pos, state)
    val powered = arm.pending ?: pistonPowered(pos, state)
    arm.pending = null
    if (arm.moving || powered == arm.extending) return
    val facing = state.pistonFacing
    val front = pos.side(facing)
    // Pulling in, the head goes first, leaving room for what the arm pulls.
    if (!powered && stateAt(front)?.isPistonHead == true) {
        set(front, blocks.stateOf(AIR))
        reshaped += front
    }
    val push = resolvePush(pos, facing, extending = powered)
    if (powered && push == null) return
    if (push != null && (powered || arm.sticky)) {
        for (at in push.broken.asReversed()) stateAt(at)?.let { breakAt(at, it, broken) }
        moveBlocks(pos, if (powered) facing else facing.opposite, push.moved, powered, arm, reshaped)
    }
    if (powered) {
        val head = if (arm.sticky) STICKY_PISTON_HEAD else PISTON_HEAD
        blocks.find(head, mapOf(FACING_DIRECTION to state.properties.getValue(FACING_DIRECTION)))?.let { set(front, it) }
        reshaped += front
    }
    arm.extending = powered
    arm.moving = true
    arm.progress = if (powered) 0f else 1f
    arm.lastProgress = arm.progress
    setBlockEntity(pos, arm.data())
    reshaped += pos
    sounds += BlockSound(pos, if (powered) PISTON_OUT else PISTON_IN, -1)
}

/**
 * Moves [moving] a block [direction], for the piston at [piston]: each
 * becomes a moving block where it's going, which the client draws sliding
 * along with the arm, and lands there once the arm's done.
 */
private fun FlatWorld.moveBlocks(piston: BlockPos, direction: Face, moving: List<BlockPos>, extending: Boolean, arm: PistonArm, reshaped: MutableCollection<BlockPos>) {
    val states = moving.associateWith { stateAt(it)!! }
    for (at in moving) set(at, blocks.stateOf(AIR))
    val movingBlock = blocks.stateOf(MOVING_BLOCK)
    for ((at, state) in states) {
        val to = at.side(direction)
        set(to, movingBlock)
        setBlockEntity(to, movingData(to, state, piston, extending))
        arm.landing[to] = state
    }
    arm.moved += moving
    reshaped += moving + arm.landing.keys
}

/** A moving block's block entity: the block that's moving, and the piston moving it. A piston moving keeps its arm's data too. */
private fun movingData(at: BlockPos, state: BlockState, piston: BlockPos, extending: Boolean): NbtMap {
    val data = NbtMap.builder()
        .putString("id", "MovingBlock")
        .putCompound("movingBlock", state.stateData())
        .putCompound("movingBlockExtra", NbtMap.builder().putString("name", AIR).putCompound("states", NbtMap.EMPTY).putInt("version", state.version).build())
        .putBoolean("expanding", extending)
        .putBoolean("isMovable", true)
        .putInt("pistonPosX", piston.x)
        .putInt("pistonPosY", piston.y)
        .putInt("pistonPosZ", piston.z)
    if (state.isPiston) {
        val arm = PistonArm(state.name == STICKY_PISTON).data().toBuilder().putInt("x", at.x).putInt("y", at.y).putInt("z", at.z)
        data.putCompound("movingEntity", arm.build())
    }
    return data.build()
}

/** A block state as the game names one in data: its name, its properties, and the version of them. */
private fun BlockState.stateData(): NbtMap = NbtMap.builder().putString("name", name).putCompound("states", properties).putInt("version", version).build()

/**
 * Moves every piston's arm along a tick, as far as it goes in a tick: half
 * a block, so a move takes two ticks, and lands what it moved on the
 * third. Where blocks changed goes in [reshaped], and where only an arm's
 * data did in [data].
 */
internal fun FlatWorld.movePistonArms(reshaped: MutableCollection<BlockPos>, data: MutableCollection<BlockPos>) {
    for ((pos, arm) in pistonArms) {
        if (!arm.moving) continue
        arm.lastProgress = arm.progress
        arm.progress = if (arm.extending) minOf(1f, arm.progress + ARM_STEP) else maxOf(0f, arm.progress - ARM_STEP)
        if (arm.progress == arm.lastProgress) {
            land(arm, reshaped)
            // The power may have changed while it moved.
            powerChanges += pos
        }
        setBlockEntity(pos, arm.data())
        data += pos
    }
}

/**
 * Puts the blocks [arm] moved where they're going, unless something's
 * taken a moving block's place. An observer moved pulses, as in Bedrock.
 */
private fun FlatWorld.land(arm: PistonArm, reshaped: MutableCollection<BlockPos>) {
    for ((at, state) in arm.landing) {
        if (stateAt(at)?.name != MOVING_BLOCK) continue
        set(at, state)
        if (state.isPiston) setBlockEntity(at, pistonArm(at, state).data())
        if (state.isObserver) observe(at, state)
        reshaped += at
    }
    arm.landing.clear()
    arm.moved.clear()
    arm.moving = false
}

/** The block [steps] blocks away on its [face] side. */
private fun BlockPos.side(face: Face, steps: Int) = BlockPos(x + face.dx * steps, y + face.dy * steps, z + face.dz * steps)

/** What a piston moves, nearest the piston first, and what it breaks. */
private class Push(val moved: List<BlockPos>, val broken: List<BlockPos>)

/**
 * What the piston at [piston], facing [facing], moves pushing out, if
 * [extending], or pulling in, as the game works it out, or null if
 * something in the way won't move, or it would move more than twelve
 * blocks. Pushing, it moves the block in front of it and those in a line
 * beyond, until air, or a block it breaks. Pulling, it moves the block in
 * front of its arm. Either way, slime and honey drag the blocks beside
 * them along, unless those are slime and honey stuck together.
 */
private fun FlatWorld.resolvePush(piston: BlockPos, facing: Face, extending: Boolean): Push? {
    val direction = if (extending) facing else facing.opposite
    val start = if (extending) piston.side(facing) else piston.side(facing).side(facing)
    val moved = mutableListOf<BlockPos>()
    val broken = mutableListOf<BlockPos>()

    fun pushable(at: BlockPos, moving: Face, breaking: Boolean, pistonFacing: Face): Boolean {
        val state = stateAt(at) ?: return false
        if (at.y !in MIN_Y..MAX_Y) return false
        if (state.name == AIR) return true
        if ((moving == Face.DOWN && at.y == MIN_Y) || (moving == Face.UP && at.y == MAX_Y)) return false
        if (state.isPiston) return pistonArms[at]?.let { !it.extending && !it.moving } ?: true
        return when (pushReaction(at, state)) {
            PushReaction.BLOCK -> false
            PushReaction.BREAK -> breaking
            PushReaction.PUSH_ONLY -> moving == pistonFacing
            PushReaction.NORMAL -> true
        }
    }

    fun reorderAtCollision(offsets: Int, index: Int) {
        val before = moved.subList(0, index).toList()
        val dragged = moved.subList(moved.size - offsets, moved.size).toList()
        val after = moved.subList(index, moved.size - offsets).toList()
        moved.clear()
        moved += before + dragged + after
    }

    lateinit var addBranches: (BlockPos) -> Boolean

    fun addLine(origin: BlockPos, lineDirection: Face): Boolean {
        var state = stateAt(origin) ?: return true
        if (state.name == AIR || !pushable(origin, direction, false, lineDirection) || origin == piston || origin in moved) return true
        var length = 1
        if (length + moved.size > PUSH_LIMIT) return false
        // Blocks behind a sticky block, dragged along with it.
        while (state.isSticky) {
            val behind = origin.side(direction.opposite, length)
            val previous = state
            state = stateAt(behind) ?: break
            if (state.name == AIR || !sticksTo(previous, state) || !pushable(behind, direction, false, direction.opposite) || behind == piston) break
            length++
            if (length + moved.size > PUSH_LIMIT) return false
        }
        var added = 0
        for (k in length - 1 downTo 0) {
            moved += origin.side(direction.opposite, k)
            added++
        }
        var step = 1
        while (true) {
            val next = origin.side(direction, step)
            val index = moved.indexOf(next)
            if (index > -1) {
                reorderAtCollision(added, index)
                for (n in 0..index + added) if (stateAt(moved[n])?.isSticky == true && !addBranches(moved[n])) return false
                return true
            }
            val nextState = stateAt(next) ?: return false
            if (nextState.name == AIR) return true
            if (!pushable(next, direction, true, direction) || next == piston) return false
            if (pushReaction(next, nextState) == PushReaction.BREAK) {
                broken += next
                return true
            }
            if (moved.size >= PUSH_LIMIT) return false
            moved += next
            added++
            step++
        }
    }

    addBranches = { from ->
        val state = stateAt(from)
        Face.entries.filter { it.axis != direction.axis }.all { side ->
            val beside = from.side(side)
            val besideState = stateAt(beside)
            besideState == null || state == null || !sticksTo(besideState, state) || addLine(beside, side)
        }
    }

    val first = stateAt(start) ?: return null
    if (!pushable(start, direction, false, facing)) {
        if (extending && pushReaction(start, first) == PushReaction.BREAK) return Push(emptyList(), listOf(start))
        return null
    }
    if (!addLine(start, direction)) return null
    var i = 0
    while (i < moved.size) {
        if (stateAt(moved[i])?.isSticky == true && !addBranches(moved[i])) return null
        i++
    }
    return Push(moved, broken)
}

/** Whether two blocks beside each other move together: either's slime or honey, but not slime and honey together. */
private fun sticksTo(one: BlockState, other: BlockState): Boolean {
    if ((one.name == HONEY && other.name == SLIME) || (one.name == SLIME && other.name == HONEY)) return false
    return one.isSticky || other.isSticky
}

/** How a block takes to a piston moving it. */
private enum class PushReaction { NORMAL, BLOCK, BREAK, PUSH_ONLY }

/**
 * How the block at [pos] takes to a piston: it won't move at all, like
 * obsidian, or anything that keeps items or data Kore doesn't carry along
 * yet; it breaks, like what another block holds up, plants and torches
 * among them, and what a placed block would replace; it moves only when
 * pushed, like glazed terracotta; or it moves either way. Rails move,
 * though they stand on the floor.
 */
private fun FlatWorld.pushReaction(pos: BlockPos, state: BlockState): PushReaction {
    val name = state.name
    return when {
        name in IMMOVABLE || state.storage != null || state.furnaceKind != null || name == BREWING_STAND || name == HOPPER ||
            state.dispenserKind != null || (blockEntityAt(pos) != null && !breaksForPistons(state)) -> PushReaction.BLOCK
        breaksForPistons(state) -> PushReaction.BREAK
        name.endsWith("glazed_terracotta") -> PushReaction.PUSH_ONLY
        else -> PushReaction.NORMAL
    }
}

/** Whether a piston breaks [state] rather than moving it. */
private fun breaksForPistons(state: BlockState): Boolean {
    val name = state.name
    if (name.endsWith("rail")) return false
    return state.replaceable || state.heldFrom != null || UPPER_BLOCK in state.properties || HEAD_PIECE in state.properties ||
        name in BROKEN_BY_PISTONS || BROKEN_BY_PISTONS_ENDINGS.any(name::endsWith)
}

/** The other half of a piston that's out, or of its head: the head in front of it, or the piston behind. */
internal fun FlatWorld.pistonPartner(pos: BlockPos, state: BlockState): BlockPos? = when {
    state.isPiston -> pos.side(state.pistonFacing).takeIf { stateAt(it)?.isPistonHead == true }
    state.isPistonHead -> {
        val facing = Face.of(state.properties[FACING_DIRECTION] as? Int ?: 0)?.let { if (it.horizontal) it.opposite else it }
        facing?.let { pos.side(it.opposite) }?.takeIf { stateAt(it)?.isPiston == true }
    }
    else -> null
}
