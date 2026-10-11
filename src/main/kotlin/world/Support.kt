package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.registry.AIR
import org.koremc.registry.BlockState

/** A block that broke, with the data it kept, like a bed's colour, which what it drops can need. */
data class BrokenBlock(val state: BlockState, val blockEntity: NbtMap? = null)

/** What a change to the world did around it: the blocks that broke, and where blocks changed shape. */
class Changes(val broken: Map<BlockPos, BrokenBlock>, val reshaped: List<BlockPos>)

/** How many blocks [settle] looks at, at most, so that nothing can keep it going for ever. */
private const val MAX_SETTLE_STEPS = 20_000

/** How far apart, at most, two tripwire hooks can be and still be strung together. */
internal const val TRIPWIRE_REACH = 41

/**
 * Plants and the like that stand on the block below, and break without
 * it, by how their names end or by their names. Two-block plants, doors
 * and attachments are known by their properties instead.
 */
private val ON_FLOOR_ENDINGS = listOf(
    "_sapling", "_tulip", "_carpet", "_pressure_plate", "rail", "_coral_fan", "_coral", "candle", "candle_cake",
    "_petals", "_standing_sign", "standing_banner", "_repeater", "_comparator", "_crop", "_fungus",
)
private val ON_FLOOR = setOf(
    "short_grass", "short_dry_grass", "tall_dry_grass", "fern", "deadbush", "bush", "firefly_bush", "dandelion", "poppy",
    "blue_orchid", "allium", "azure_bluet", "oxeye_daisy", "cornflower", "lily_of_the_valley", "wither_rose", "torchflower",
    "open_eyeblossom", "closed_eyeblossom", "golden_dandelion", "cactus", "cactus_flower", "reeds", "bamboo", "bamboo_sapling",
    "azalea", "flowering_azalea", "nether_sprouts", "crimson_roots", "warped_roots", "nether_wart", "wheat", "carrots",
    "potatoes", "beetroot", "sweet_berry_bush", "sea_pickle", "turtle_egg", "snow_layer", "redstone_wire", "cake",
    "standing_sign", "leaf_litter", "wildflowers", "brown_mushroom", "red_mushroom", "flower_pot", "kelp", "melon_stem",
    "pumpkin_stem",
).mapTo(HashSet()) { "minecraft:$it" }

/** Blocks that hang from the block above, and break without it. */
private val FROM_CEILING = setOf(
    "hanging_roots", "spore_blossom", "pale_hanging_moss", "cave_vines", "cave_vines_body_with_berries",
    "cave_vines_head_with_berries", "weeping_vines",
).mapTo(HashSet()) { "minecraft:$it" }

/**
 * For a block another holds up, the side the block holding it is on:
 * a torch, button, lever, ladder, sign, banner or tripwire hook on its
 * side, a plant or rail on the floor, a lantern or roots from a ceiling,
 * or a door or tall plant on its top. Null for a block that stands by
 * itself. The top half of a door or plant is held by its bottom half,
 * which [otherHalf] follows.
 */
internal val BlockState.heldFrom: Face?
    get() {
        if (properties[UPPER_BLOCK] == 0.toByte()) return Face.DOWN
        properties[TORCH_FACING]?.let { leaning -> return if (leaning == "top") Face.DOWN else Face.entries.find { it.id == leaning } }
        // A lever names the face it points out of, or the axis it lies along on a floor (up) or ceiling (down).
        (properties[LEVER_DIRECTION] as? String)?.let { pointing ->
            return when {
                pointing.startsWith("up") -> Face.DOWN
                pointing.startsWith("down") -> Face.UP
                else -> Face.entries.find { it.id == pointing }?.opposite
            }
        }
        val pointing = (properties[FACING_DIRECTION] as? Int)?.let(Face::of)
        when {
            // These point out of the face they're on.
            name.endsWith("button") || name == "minecraft:ladder" || name.endsWith("wall_sign") || name == "minecraft:wall_banner" ||
                name == "minecraft:frame" || name == "minecraft:glow_frame" -> return pointing?.opposite
            name.endsWith("_coral_wall_fan") -> return (properties["coral_direction"] as? Int)?.let { CORAL_SIDES.getOrNull(it) }?.opposite
            name == "minecraft:tripwire_hook" || name == "minecraft:cocoa" -> return (properties[DIRECTION] as? Int)?.let(Face::ofLegacyIndex)?.opposite
            name == "minecraft:shelf_mushroom" -> return Face.entries.find { it.id == properties[CARDINAL] }?.opposite
            name == "minecraft:bell" -> return when (properties[ATTACHMENT]) {
                "standing" -> Face.DOWN
                "hanging" -> Face.UP
                "side" -> (properties[DIRECTION] as? Int)?.let(Face::ofLegacyIndex)?.opposite
                else -> null
            }
            name.endsWith("_hanging_sign") -> return if (properties[HANGING] == 1.toByte()) Face.UP else null
            (name.endsWith("lantern") && HANGING in properties) || name == "minecraft:pointed_dripstone" || name == "minecraft:mangrove_propagule" ->
                return if (properties[HANGING] == 1.toByte()) Face.UP else Face.DOWN
            name in FROM_CEILING -> return Face.UP
            name in ON_FLOOR || ON_FLOOR_ENDINGS.any(name::endsWith) -> return Face.DOWN
        }
        return null
    }

/** The sides coral fans on a wall face, in the order their `coral_direction` numbers them. */
private val CORAL_SIDES = listOf(Face.WEST, Face.EAST, Face.NORTH, Face.SOUTH)

/**
 * Whether [holder] holds up [block] on its [face]. Buttons, levers and
 * ladders need a whole face to be fixed to, as torches do, which stand on
 * any whole top, or a fence or wall. Anything else stands on, hangs from
 * or leans on any block a placed block wouldn't replace.
 */
internal fun FlatWorld.holdsUp(holderPos: BlockPos, face: Face, block: BlockState): Boolean {
    val holder = stateAt(holderPos) ?: return false
    return when {
        TORCH_FACING in block.properties -> if (face == Face.UP) holder.holdsTorchOnTop else holder.hasFullFace(face)
        block.name.endsWith("button") || block.name == "minecraft:lever" || block.name == "minecraft:ladder" -> holder.hasFullFace(face)
        else -> !holder.replaceable
    }
}

/**
 * Whether [state] can stay at [pos]: whatever holds it up is still there,
 * and a cactus has no block beside it. Scaffolding stays within reach of
 * what holds it, and vines and glow lichen keep at least one face.
 */
internal fun FlatWorld.survives(pos: BlockPos, state: BlockState): Boolean {
    val holder = state.heldFrom
    if (holder != null && !holdsUp(pos.side(holder), holder.opposite, state)) return false
    if (state.name == "minecraft:cactus" && AROUND.any { stateAt(pos.side(it))?.isFullCube == true }) return false
    if (state.name == SCAFFOLDING && scaffoldingDistance(pos) >= SCAFFOLDING_MAX_DISTANCE) return false
    return true
}

/** The four sides around a block. */
internal val AROUND = listOf(Face.NORTH, Face.EAST, Face.SOUTH, Face.WEST)

/**
 * Breaks the block at [pos], [state], leaving air and forgetting its block
 * entity, and notes it in [broken]. A chest it was paired with stands
 * alone again: returns where that is, as the data it keeps changed.
 */
internal fun FlatWorld.breakAt(pos: BlockPos, state: BlockState, broken: MutableMap<BlockPos, BrokenBlock>): BlockPos? {
    val blockEntity = blockEntityAt(pos)
    broken[pos] = BrokenBlock(state, blockEntity)
    set(pos, blocks.stateOf(AIR))
    return blockEntity?.let { unpairChestFrom(it) }
}

/**
 * Brings the world around [changed] in line with it, and around each
 * block that changes in turn: blocks that can no longer stay where they
 * are break, like a flower whose ground went, and blocks shaped by their
 * neighbours take their new shape, like fences, walls, stairs, tripwire,
 * dripstone and scaffolding. Vines and glow lichen let go of faces with
 * nothing behind them, and break when they're left with none. A chest
 * whose pair broke stands alone. Returns what broke, and where blocks
 * changed shape or the data they keep changed.
 */
fun FlatWorld.settle(changed: Collection<BlockPos>): Changes {
    val broken = linkedMapOf<BlockPos, BrokenBlock>()
    val reshaped = LinkedHashSet<BlockPos>()
    val toCheck = ArrayDeque<BlockPos>()
    fun around(pos: BlockPos) {
        Face.entries.mapTo(toCheck, pos::side)
        alsoAffectedBy(pos).let(toCheck::addAll)
    }
    changed.forEach(::around)

    var steps = 0
    while (toCheck.isNotEmpty() && steps++ < MAX_SETTLE_STEPS) {
        val at = toCheck.removeFirst()
        val state = stateAt(at)?.takeIf { it.name != AIR } ?: continue
        val faces = remainingFaces(at, state)
        if (!survives(at, state) || faces?.isEmpty() == true) {
            breakAt(at, state, broken)?.let(reshaped::add)
            state.otherHalf(at)?.let { other -> stateAt(other)?.takeIf { it.name == state.name }?.let { breakAt(other, it, broken); around(other) } }
            around(at)
            continue
        }
        var shaped = joined(at, state)
        if (faces != null) shaped = withFaces(shaped, faces)
        if (shaped == state) continue
        set(at, shaped)
        reshaped += at
        around(at)
    }
    reshaped.removeAll(broken.keys)
    return Changes(broken, reshaped.toList())
}

/**
 * Blocks besides its neighbours that a change at [pos] can reshape: the
 * tripwire hooks and tripwire along its lines, for a change that can
 * string them together or part them.
 */
private fun FlatWorld.alsoAffectedBy(pos: BlockPos): List<BlockPos> = AROUND.flatMap { side ->
    (1..TRIPWIRE_REACH).asSequence()
        .map { BlockPos(pos.x + side.dx * it, pos.y, pos.z + side.dz * it) }
        .takeWhile { stateAt(it)?.name.let { name -> name == TRIPWIRE || name == TRIPWIRE_HOOK } }
        .toList()
}

/**
 * The faces vines or glow lichen at [pos] can keep: those with a block
 * behind them, and for vines, those the vines above hang down from too.
 * Null for a block that isn't one of those.
 */
private fun FlatWorld.remainingFaces(pos: BlockPos, state: BlockState): Set<Face>? {
    val (property, bits) = when {
        MULTI_FACE in state.properties -> MULTI_FACE to MULTI_FACE_BITS
        VINE_FACES in state.properties -> VINE_FACES to VINE_BITS
        else -> return null
    }
    val faces = facesIn(bits, state.properties[property] as Int)
    val above = stateAt(pos.side(Face.UP))?.takeIf { it.name == state.name }
    val hanging = above?.let { facesIn(bits, it.properties[property] as Int) }.orEmpty()
    return faces.filterTo(HashSet()) { face -> stateAt(pos.side(face))?.replaceable == false || (property == VINE_FACES && face in hanging) }
}

/** [state], vines or glow lichen, covering just [faces]. */
private fun FlatWorld.withFaces(state: BlockState, faces: Set<Face>): BlockState {
    val (property, bits) = if (MULTI_FACE in state.properties) MULTI_FACE to MULTI_FACE_BITS else VINE_FACES to VINE_BITS
    return blocks.find(state.name, state.properties + (property to faceBits(bits, faces))) ?: state
}
