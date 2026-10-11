package org.koremc.world

import org.koremc.registry.AIR
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState

/** A side of a block, in the order the client numbers them. */
enum class Face(val dx: Int, val dy: Int, val dz: Int) {
    DOWN(0, -1, 0),
    UP(0, 1, 0),
    NORTH(0, 0, -1),
    SOUTH(0, 0, 1),
    WEST(-1, 0, 0),
    EAST(1, 0, 0);

    val opposite: Face get() = entries[ordinal xor 1]

    /** The axis it lies across, as pillars name it. */
    val axis: String get() = when (this) {
        DOWN, UP -> "y"
        NORTH, SOUTH -> "z"
        WEST, EAST -> "x"
    }

    /** Its name as block properties spell it. */
    val id: String get() = name.lowercase()

    /** The side a quarter turn clockwise, seen from above. Only the four around are turned. */
    val clockwise: Face get() = when (this) {
        NORTH -> EAST
        EAST -> SOUTH
        SOUTH -> WEST
        WEST -> NORTH
        else -> this
    }

    val counterClockwise: Face get() = clockwise.opposite

    companion object {
        /** The face numbered [id], or null for a number that isn't one, like the 255 for clicking nothing. */
        fun of(id: Int): Face? = entries.getOrNull(id)
    }
}

/** The block next to this one, through its [face]. */
fun BlockPos.side(face: Face) = BlockPos(x + face.dx, y + face.dy, z + face.dz)

/** The middle of this block, where sounds and particles from it start. */
val BlockPos.centre get() = Vec3(x + 0.5f, y + 0.5f, z + 0.5f)

/** Which of the four sides a player looking along [yaw] faces. A yaw of 0 looks south, and 90 west. */
fun facing(yaw: Float): Face = when (Math.floorMod(Math.round(yaw / 90f), 4)) {
    0 -> Face.SOUTH
    1 -> Face.WEST
    2 -> Face.NORTH
    else -> Face.EAST
}

/** Blocks that a placed block takes the place of, rather than going beside. */
private val REPLACEABLE = setOf(
    AIR, "minecraft:water", "minecraft:flowing_water", "minecraft:lava", "minecraft:flowing_lava",
    "minecraft:fire", "minecraft:soul_fire", "minecraft:short_grass", "minecraft:fern", "minecraft:deadbush",
    "minecraft:bush", "minecraft:short_dry_grass", "minecraft:tall_dry_grass", "minecraft:seagrass",
    "minecraft:crimson_roots", "minecraft:warped_roots", "minecraft:nether_sprouts", "minecraft:hanging_roots",
    "minecraft:glow_lichen", "minecraft:vine", "minecraft:snow_layer", "minecraft:structure_void",
)

/** Blocks that only creative players break. */
private val UNBREAKABLE = setOf(
    "minecraft:bedrock", "minecraft:barrier", "minecraft:end_portal_frame", "minecraft:end_portal", "minecraft:end_gateway",
    "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
    "minecraft:structure_block", "minecraft:jigsaw",
)

internal const val UPPER_BLOCK = "upper_block_bit"
private const val VERTICAL_HALF = "minecraft:vertical_half"
private const val TORCH_FACING = "torch_facing_direction"
private const val LEVER_DIRECTION = "lever_direction"
private const val DOOR_HINGE = "door_hinge_bit"

/** Where a torch that can't lean on the block it was placed against looks for one that can hold it, in the game's order. */
private val TORCH_SUPPORTS = listOf(Face.SOUTH, Face.WEST, Face.NORTH, Face.EAST, Face.DOWN)

/** Whether only creative players can break this block. */
val BlockState.unbreakable get() = name in UNBREAKABLE

/** Whether a placed block takes this one's place, rather than going beside it. */
val BlockState.replaceable get() = name in REPLACEABLE

/**
 * The block placed by the item called [item] when the item isn't a block
 * itself. A door's item carries no block state, but places the door of the
 * same name. Null for any other item.
 */
fun BlockPalette.blockPlacedBy(item: String): BlockState? = if (item.endsWith("_door")) defaultOf(item) else null

/**
 * Places [block] as a player does, against [face] of the block at
 * [clicked], having clicked [clickY] up that face while facing [facing].
 * Returns where blocks changed: nothing, if there's no room. Kore follows
 * the game's rules for the common cases:
 *
 * - A new block goes in the space beyond the face, if that space holds
 *   nothing a block can't replace, like air or short grass. If the clicked
 *   block is one of those, the new block takes its place instead, as if
 *   placed on top of it.
 * - A slab onto the open half of the same slab, or into a space holding
 *   one, makes a double slab.
 * - A plant two blocks tall, or a door, needs the space above as well,
 *   and a whole top to stand on. A door hinges on its right if there's a
 *   door to its left to pair with, or a wall to its right and not its left.
 * - Buttons, levers and ladders need a whole face to be fixed to, like a
 *   full block's or the back of stairs.
 * - A torch leans on the block it was placed against, if that has a whole
 *   face there. If not, it leans on a neighbour that does, or stands on
 *   the floor. With none of those, it isn't placed.
 * - Blocks that turn take their state from the face and the player, as
 *   [placedState] explains.
 * - Fences, panes, walls, fence gates and stairs take their shape from the
 *   blocks around them, as [joined] explains. The blocks around don't change
 *   here: [updateAround] brings them in line.
 */
fun FlatWorld.place(block: BlockState, clicked: BlockPos, face: Face, clickY: Float, facing: Face): List<BlockPos> {
    val clickedState = stateAt(clicked) ?: return emptyList()
    val replacing = clickedState.replaceable
    val target = if (replacing) clicked else clicked.side(face)
    if (target.y !in MIN_Y..MAX_Y) return emptyList()

    val openFace = if (clickedState.properties[VERTICAL_HALF] == "top") Face.DOWN else Face.UP
    if (clickedState.name == block.name && face == openFace) {
        blocks.doubleSlabOf(clickedState)?.let { set(clicked, it); return listOf(clicked) }
    }
    val targetState = stateAt(target) ?: return emptyList()
    if (targetState.name == block.name) {
        blocks.doubleSlabOf(targetState)?.let { set(target, it); return listOf(target) }
    }
    if (!targetState.replaceable) return emptyList()

    val placedOn = if (replacing) Face.UP else face
    var state = blocks.placedState(block, placedOn, clickY, facing)
    if (TORCH_FACING in state.properties) {
        state = torchAt(target, placedOn, state) ?: return emptyList()
    } else {
        val holder = state.heldFrom
        if (holder != null && stateAt(target.side(holder))?.hasFullFace(holder.opposite) != true) return emptyList()
    }
    if (DOOR_HINGE in state.properties) {
        val hinge = (if (hingesRight(target, facing)) 1 else 0).toByte()
        state = blocks.find(state.name, state.properties + (DOOR_HINGE to hinge)) ?: state
    }
    state = joined(target, state)
    val upper = blocks.upperHalfOf(state)
    val above = target.side(Face.UP)
    if (upper != null && (above.y > MAX_Y || stateAt(above)?.replaceable != true)) return emptyList()

    set(target, state)
    if (upper == null) return listOf(target)
    set(above, upper)
    return listOf(target, above)
}

/**
 * Breaks the block at [pos], leaving air, along with whatever goes with
 * it: the other half of a door or a tall plant, and anything it held up,
 * like a torch on its side or a door on its top, and so on from those.
 * Returns what broke, where.
 */
fun FlatWorld.breakBlock(pos: BlockPos): Map<BlockPos, BlockState> {
    val air = blocks.stateOf(AIR)
    val broken = linkedMapOf<BlockPos, BlockState>()
    val toBreak = ArrayDeque(listOf(pos))
    while (toBreak.isNotEmpty()) {
        val at = toBreak.removeFirst()
        val state = stateAt(at)?.takeIf { at !in broken && it != air } ?: continue
        broken[at] = state
        set(at, air)

        state.otherHalf(at)?.takeIf { stateAt(it)?.name == state.name }?.let(toBreak::add)
        Face.entries.map(at::side).filterTo(toBreak) { next -> stateAt(next)?.heldFrom?.let(next::side) == at }
    }
    return broken
}

/** Where the other half of a door or tall plant at [pos] is, or null for a block that's one block. */
internal fun BlockState.otherHalf(pos: BlockPos): BlockPos? = when (properties[UPPER_BLOCK]) {
    0.toByte() -> pos.side(Face.UP)
    1.toByte() -> pos.side(Face.DOWN)
    else -> null
}

/**
 * Whether a door at [pos], placed by a player facing [facing], hinges on
 * its right: when there's a door to its left to pair with, or a whole
 * block to its right and not its left.
 */
private fun FlatWorld.hingesRight(pos: BlockPos, facing: Face): Boolean {
    val left = stateAt(pos.side(facing.counterClockwise))
    val right = stateAt(pos.side(facing.clockwise))
    return left?.name?.endsWith("_door") == true || (right?.isFullCube == true && left?.isFullCube != true)
}

/**
 * [torch] at [pos], leaning on the block it was placed against [face] of,
 * if that can hold it. If not, it leans on the first neighbour that can,
 * looking in [TORCH_SUPPORTS] order, which ends with the floor. Null if
 * nothing around can hold it.
 */
private fun FlatWorld.torchAt(pos: BlockPos, face: Face, torch: BlockState): BlockState? {
    // Torches don't hang from ceilings.
    val placedAgainst = face.opposite.takeIf { face != Face.DOWN }
    val support = (listOfNotNull(placedAgainst) + TORCH_SUPPORTS).firstOrNull { side ->
        val neighbour = stateAt(pos.side(side)) ?: return@firstOrNull false
        if (side == Face.DOWN) neighbour.holdsTorchOnTop else neighbour.hasFullFace(side.opposite)
    } ?: return null
    // A torch names the side it leans on, or "top" when it stands on the floor.
    return blocks.find(torch.name, torch.properties + (TORCH_FACING to if (support == Face.DOWN) "top" else support.id))
}

/**
 * For a block another holds up, like a torch, button, lever or ladder on
 * its side, or a door or tall plant on its top, the side the block holding
 * it is on. Null for a block that stands by itself. The top half of a door
 * or plant is held by its bottom half, which [otherHalf] follows.
 */
private val BlockState.heldFrom: Face?
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
        // Buttons and ladders point out of the face they're on.
        if (name.endsWith("button") || name == "minecraft:ladder") return (properties["facing_direction"] as? Int)?.let { Face.of(it) }?.opposite
        return null
    }

/**
 * The state of [block] once placed against [face], by a player facing
 * [facing] who clicked [clickY] up the face. Blocks that turn follow the
 * client's own rules for the properties most share:
 *
 * - Pillars, like logs, lie along the face's axis.
 * - Slabs, stairs and trapdoors take the top half of a space when the
 *   player clicks a block's underside or the top half of its side.
 * - Stairs climb the way the player faces.
 * - Blocks with a front, like furnaces, chests and glazed terracotta, turn
 *   it to the player. Doors and fence gates face the way the player does,
 *   doors naming the side a quarter turn clockwise from it, and trapdoors
 *   hinge on the side away from the player.
 * - Attachments, like buttons, levers and ladders, point out of the face.
 *   A lever on a floor or ceiling lies the way the player faces. Torches
 *   have rules of their own, which [place] follows.
 *
 * Any other property keeps the item's value, as does a block whose rules
 * give a state that doesn't exist.
 */
fun BlockPalette.placedState(block: BlockState, face: Face, clickY: Float, facing: Face): BlockState {
    val top = face == Face.DOWN || (clickY > 0.5f && face != Face.UP)
    val properties = block.properties.mapValues { (property, value) ->
        when (property) {
            "pillar_axis" -> face.axis
            VERTICAL_HALF -> if (top) "top" else "bottom"
            "upside_down_bit" -> (if (top) 1 else 0).toByte()
            "weirdo_direction" -> STAIRS_CLIMB.indexOf(facing)
            "minecraft:cardinal_direction" -> when {
                block.name.endsWith("_door") -> facing.clockwise.id
                block.name.endsWith("fence_gate") -> facing.id
                else -> facing.opposite.id
            }
            // A trapdoor numbers the way it faces, from its hinge out, as stairs number the ways they climb.
            "direction" -> if (block.name.endsWith("trapdoor")) STAIRS_CLIMB.indexOf(facing.opposite) else value
            "facing_direction" -> if (block.name.endsWith("glazed_terracotta")) facing.opposite.ordinal else face.ordinal
            "minecraft:block_face" -> face.id
            LEVER_DIRECTION -> when (face) {
                Face.UP, Face.DOWN -> face.id + if (facing.axis == "x") "_east_west" else "_north_south"
                else -> face.id
            }
            else -> value
        }
    }
    return find(block.name, properties) ?: block
}

/** The double slab [slab] makes with another of its kind, or null if it isn't a slab that does. */
private fun BlockPalette.doubleSlabOf(slab: BlockState): BlockState? {
    val half = slab.properties[VERTICAL_HALF] ?: return null
    if (!slab.name.endsWith("_slab") || "double" in slab.name) return null
    // Copper slabs put "double" first.
    val name = if (slab.name.endsWith("cut_copper_slab")) slab.name.replace("cut_copper_slab", "double_cut_copper_slab") else slab.name.removeSuffix("_slab") + "_double_slab"
    return find(name, mapOf(VERTICAL_HALF to half))
}

/** The top half of [state], for a plant two blocks tall, or null for a block that's one. */
private fun BlockPalette.upperHalfOf(state: BlockState): BlockState? =
    if (state.properties[UPPER_BLOCK] == 0.toByte()) find(state.name, state.properties + (UPPER_BLOCK to 1.toByte())) else null
