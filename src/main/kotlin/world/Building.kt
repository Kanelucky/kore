package org.koremc.world

import org.koremc.registry.AIR
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import org.koremc.registry.ItemStack
import kotlin.math.abs
import kotlin.math.floor

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

    /** Whether it's one of the four sides around, rather than the top or bottom. */
    val horizontal: Boolean get() = dy == 0

    /** The yaw of a player looking towards this side, for the four around: south is 0, and west 90. */
    val yaw: Float get() = when (this) {
        SOUTH -> 0f
        WEST -> 90f
        NORTH -> 180f
        EAST -> 270f
        else -> 0f
    }

    /**
     * The number older properties, like `direction`, give one of the four
     * sides around: south, west, north and east, from 0.
     */
    val legacyIndex: Int get() = LEGACY_ORDER.indexOf(this)

    companion object {
        /** The face numbered [id], or null for a number that isn't one, like the 255 for clicking nothing. */
        fun of(id: Int): Face? = entries.getOrNull(id)

        /** The side around numbered [index] as older properties number them, or null for a number that isn't one. */
        fun ofLegacyIndex(index: Int): Face? = LEGACY_ORDER.getOrNull(index)

        private val LEGACY_ORDER = listOf(SOUTH, WEST, NORTH, EAST)
    }
}

/** The block next to this one, through its [face]. */
fun BlockPos.side(face: Face) = BlockPos(x + face.dx, y + face.dy, z + face.dz)

/** The middle of this block, where sounds and particles from it start. */
val BlockPos.centre get() = Vec3(x + 0.5f, y + 0.5f, z + 0.5f)

/** The corner of this block with the lowest coordinates, where the client puts the cracks of a block being broken. */
val BlockPos.corner get() = Vec3(x.toFloat(), y.toFloat(), z.toFloat())

/** Which of the four sides a player looking along [yaw] faces. A yaw of 0 looks south, and 90 west. */
fun facing(yaw: Float): Face = when (Math.floorMod(Math.round(yaw / 90f), 4)) {
    0 -> Face.SOUTH
    1 -> Face.WEST
    2 -> Face.NORTH
    else -> Face.EAST
}

/**
 * Who places a block, as far as the block cares: which way they face,
 * the exact way they look, for blocks that turn in sixteen steps, where
 * their eyes are, for blocks that can face them from above or below, and
 * whether they sneak.
 */
data class Placer(val facing: Face, val yaw: Float = facing.yaw, val eyes: Vec3? = null, val sneaking: Boolean = false) {
    /**
     * The side of a block placed at [pos] that faces the placer, which
     * blocks like dispensers and pistons turn their front to: up or down
     * when they're right beside it and their eyes are well above it or
     * below it, and otherwise the side around towards them. This is the
     * game's rule, not the way they look.
     */
    fun facingFrom(pos: BlockPos): Face {
        val eyes = eyes
        if (eyes != null && abs(floor(eyes.x).toInt() - pos.x) < 2 && abs(floor(eyes.z).toInt() - pos.z) < 2) {
            if (eyes.y - pos.y > 2) return Face.UP
            if (pos.y - eyes.y > 0) return Face.DOWN
        }
        return facing.opposite
    }

    /**
     * Which of sixteen steps around a block turned to face the placer
     * points, as standing signs and banners count them: 0 faces south,
     * and each step turns a sixteenth of the way round, clockwise.
     */
    val rotationTowards: Int get() = Math.floorMod(floor((yaw + 180f) * 16f / 360f + 0.5f).toInt(), 16)
}

/** Blocks that a placed block takes the place of, rather than going beside. */
private val REPLACEABLE = setOf(
    AIR, "minecraft:water", "minecraft:flowing_water", "minecraft:lava", "minecraft:flowing_lava",
    "minecraft:fire", "minecraft:soul_fire", "minecraft:short_grass", "minecraft:fern", "minecraft:deadbush",
    "minecraft:bush", "minecraft:short_dry_grass", "minecraft:tall_dry_grass", "minecraft:seagrass",
    "minecraft:crimson_roots", "minecraft:warped_roots", "minecraft:nether_sprouts", "minecraft:hanging_roots",
    "minecraft:glow_lichen", "minecraft:vine", "minecraft:snow_layer", "minecraft:structure_void",
)

internal const val UPPER_BLOCK = "upper_block_bit"
internal const val HEAD_PIECE = "head_piece_bit"
internal const val VERTICAL_HALF = "minecraft:vertical_half"
internal const val TORCH_FACING = "torch_facing_direction"
internal const val LEVER_DIRECTION = "lever_direction"
private const val DOOR_HINGE = "door_hinge_bit"
internal const val FACING_DIRECTION = "facing_direction"
internal const val DIRECTION = "direction"
internal const val HANGING = "hanging"
internal const val ATTACHMENT = "attachment"
internal const val MULTI_FACE = "multi_face_direction_bits"
internal const val VINE_FACES = "vine_direction_bits"

/** Where a torch that can't lean on the block it was placed against looks for one that can hold it, in the game's order. */
private val TORCH_SUPPORTS = listOf(Face.SOUTH, Face.WEST, Face.NORTH, Face.EAST, Face.DOWN)

/** Whether a placed block takes this one's place, rather than going beside it. */
val BlockState.replaceable get() = name in REPLACEABLE

/**
 * Places [block] as [placer] does, against [face] of the block at
 * [clicked], having clicked [clickY] up that face, from [item], if it
 * came from one. Returns where blocks, or the data blocks keep, changed:
 * nothing, if there's no room. Kore follows the game's rules for the
 * common cases:
 *
 * - A new block goes in the space beyond the face, if that space holds
 *   nothing a block can't replace, like air or short grass. If the clicked
 *   block is one of those, the new block takes its place instead, as if
 *   placed on top of it.
 * - A slab onto the open half of the same slab, or into a space holding
 *   one, makes a double slab. Candles, sea pickles, snow, petals and turtle
 *   eggs pile onto their own kind, and glow lichen and vines spread over
 *   another face of the space they're in.
 * - A plant two blocks tall, a door or a bed needs the space beyond as
 *   well. A door hinges on its right if there's a door to its left to pair
 *   with, or a wall to its right and not its left.
 * - Buttons, levers and ladders need a whole face to be fixed to, like a
 *   full block's or the back of stairs.
 * - A torch leans on the block it was placed against, if that has a whole
 *   face there. If not, it leans on a neighbour that does, or stands on
 *   the floor. With none of those, it isn't placed.
 * - Signs, banners and coral fans placed against a side take their kind on
 *   a wall. Scaffolding climbs its own column or reaches out from it, rails
 *   join the rails around them, and chests pair with a chest beside them.
 * - Blocks that turn take their state from the face and the player, as
 *   [placedState] explains.
 * - Fences, panes, walls, fence gates and stairs take their shape from the
 *   blocks around them, as [joined] explains. The blocks around don't change
 *   here: [settle] brings them in line.
 */
fun FlatWorld.place(block: BlockState, clicked: BlockPos, face: Face, clickY: Float, placer: Placer, item: ItemStack? = null): List<BlockPos> {
    val clickedState = stateAt(clicked) ?: return emptyList()
    if (block.name == SCAFFOLDING && clickedState.name == SCAFFOLDING) return placeScaffolding(block, clicked, face, placer)

    // Onto the clicked block itself, or into the space beyond, when either holds the same block with room for more.
    val openFace = if (clickedState.properties[VERTICAL_HALF] == "top") Face.DOWN else Face.UP
    if (clickedState.name == block.name && face == openFace) {
        blocks.doubleSlabOf(clickedState)?.let { set(clicked, it); return listOf(clicked) }
    }
    blocks.piledOnto(clickedState, block, face)?.let { set(clicked, it); return listOf(clicked) }

    val replacing = clickedState.replaceable && !(clickedState.name == block.name && block.name == SNOW_LAYER)
    val target = if (replacing) clicked else clicked.side(face)
    if (target.y !in MIN_Y..MAX_Y) return emptyList()
    val targetState = stateAt(target) ?: return emptyList()
    if (targetState.name == block.name) {
        blocks.doubleSlabOf(targetState)?.let { set(target, it); return listOf(target) }
        blocks.piledOnto(targetState, block, face)?.let { set(target, it); return listOf(target) }
        blocks.spreadOver(targetState, face)?.let { set(target, it); return listOf(target) }
    }
    if (!targetState.replaceable) return emptyList()

    val placedOn = if (replacing) Face.UP else face
    var state = block
    if (placedOn.horizontal) state = blocks.wallVariantOf(block) ?: state
    if (state.name.endsWith("standing_sign") || state.name.endsWith("standing_banner")) {
        if (placedOn != Face.UP) return emptyList()
    }
    state = blocks.placedState(state, target, placedOn, clickY, placer)
    state = when {
        TORCH_FACING in state.properties -> torchAt(target, placedOn, state) ?: return emptyList()
        state.name == SCAFFOLDING -> scaffoldingAt(target, state) ?: return emptyList()
        state.isLantern -> lanternAt(target, placedOn, state) ?: return emptyList()
        state.name == "minecraft:bell" -> bellAt(target, placedOn, state)
        state.name.endsWith("_hanging_sign") -> hangingSignAt(target, placedOn, state, placer) ?: return emptyList()
        else -> state
    }
    val holder = state.heldFrom
    if (holder != null && !holdsUp(target.side(holder), holder.opposite, state)) return emptyList()
    if (DOOR_HINGE in state.properties) {
        val hinge = (if (hingesRight(target, placer.facing)) 1 else 0).toByte()
        state = blocks.find(state.name, state.properties + (DOOR_HINGE to hinge)) ?: state
    }
    state = joined(target, state)

    // Blocks that take two spaces: tall plants and doors reach up, and beds reach the way the player faces.
    val upper = blocks.upperHalfOf(state)
    val second = when {
        upper != null -> target.side(Face.UP) to upper
        HEAD_PIECE in state.properties -> target.side(placer.facing) to (blocks.find(state.name, state.properties + (HEAD_PIECE to 1.toByte())) ?: return emptyList())
        else -> null
    }
    if (second != null) {
        val (at, half) = second
        if (at.y !in MIN_Y..MAX_Y || stateAt(at)?.replaceable != true) return emptyList()
        if (upper == null && stateAt(at.side(Face.DOWN))?.replaceable != false) return emptyList() // A bed's head needs a floor too
        set(target, state)
        set(at, half)
    } else {
        set(target, state)
    }

    val placed = listOfNotNull(target, second?.first)
    placed.forEach { at -> blockEntityFor(stateAt(at) ?: return@forEach, at, placer, item)?.let { setBlockEntity(at, it) } }
    return placed + when {
        RAIL_DIRECTION in state.properties -> railPlaced(target, placer.facing)
        state.isChest -> pairChest(target)
        else -> emptyList()
    }
}

/**
 * Breaks the block at [pos], leaving air, along with whatever goes with
 * it: the other half of a door, bed or tall plant, and anything that can
 * no longer stand where it is, like a torch on its side or a flower on its
 * top, and so on from those. Blocks around it that join their neighbours
 * take their new shape. Returns what broke, and what changed shape.
 */
fun FlatWorld.breakBlock(pos: BlockPos): Changes {
    val state = stateAt(pos)?.takeIf { it.name != AIR } ?: return Changes(emptyMap(), emptyList())
    val broken = linkedMapOf<BlockPos, BrokenBlock>()
    val unpaired = listOfNotNull(breakAt(pos, state, broken))
    state.otherHalf(pos)?.let { other -> stateAt(other)?.takeIf { it.name == state.name }?.let { breakAt(other, it, broken) } }
    val settled = settle(broken.keys)
    return Changes(broken + settled.broken, (unpaired + settled.reshaped).distinct())
}

/** Where the other half of a door, bed or tall plant at [pos] is, or null for a block that's one block. */
internal fun BlockState.otherHalf(pos: BlockPos): BlockPos? {
    when (properties[UPPER_BLOCK]) {
        0.toByte() -> return pos.side(Face.UP)
        1.toByte() -> return pos.side(Face.DOWN)
    }
    val head = properties[HEAD_PIECE] ?: return null
    // A bed's foot points at its head.
    val pointing = bedFacing ?: return null
    return if (head == 1.toByte()) pos.side(pointing.opposite) else pos.side(pointing)
}

/** Which way a bed points, from its foot to its head. */
private val BlockState.bedFacing: Face?
    get() = (properties[DIRECTION] as? Int)?.let(Face::ofLegacyIndex) ?: Face.entries.find { it.id == properties[CARDINAL] }

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
 * [lantern] at [pos]: hanging from the block above when placed against its
 * underside, or when there's nothing below to stand on, and standing
 * otherwise. Null if neither holds it.
 */
private fun FlatWorld.lanternAt(pos: BlockPos, face: Face, lantern: BlockState): BlockState? {
    val below = stateAt(pos.side(Face.DOWN))?.hasFullFace(Face.UP) == true
    val above = stateAt(pos.side(Face.UP))?.let { it.hasFullFace(Face.DOWN) || it.name.endsWith("chain") } == true
    val hanging = when {
        face == Face.DOWN && above -> true
        below -> false
        above -> true
        else -> return null
    }
    return blocks.find(lantern.name, lantern.properties + (HANGING to bit(hanging)))
}

/**
 * [bell] at [pos], placed against [face]: standing on a floor or hanging
 * from a ceiling, or fixed to the wall behind it, or to walls on both
 * sides when there's one opposite too.
 */
private fun FlatWorld.bellAt(pos: BlockPos, face: Face, bell: BlockState): BlockState {
    val attachment = when (face) {
        Face.UP -> "standing"
        Face.DOWN -> "hanging"
        else -> if (stateAt(pos.side(face))?.replaceable == false) "multiple" else "side"
    }
    return blocks.find(bell.name, bell.properties + (ATTACHMENT to attachment)) ?: bell
}

/**
 * A hanging sign at [pos], placed against [face]. Under a ceiling, it
 * hangs from chains at its edges, facing away from [placer], when the
 * block above has a whole underside; otherwise, or when the player
 * sneaks, it hangs from one chain in its middle and turns in sixteen
 * steps, as a standing sign does. Against a wall, it sticks out of the
 * wall on a bracket, its faces at right angles to the wall.
 */
private fun FlatWorld.hangingSignAt(pos: BlockPos, face: Face, sign: BlockState, placer: Placer): BlockState? {
    val changes: Map<String, Any> = when (face) {
        Face.DOWN -> {
            val above = stateAt(pos.side(Face.UP)) ?: return null
            val centred = placer.sneaking || !above.hasFullFace(Face.DOWN)
            mapOf(
                HANGING to bit(true),
                "attached_bit" to bit(centred),
                FACING_DIRECTION to placer.facing.opposite.ordinal,
                "ground_sign_direction" to if (centred) placer.rotationTowards else 0,
            )
        }
        Face.UP -> return null
        else -> {
            var facing = face.opposite.clockwise
            if (facing == placer.facing) facing = facing.opposite
            mapOf(HANGING to bit(false), "attached_bit" to bit(false), FACING_DIRECTION to facing.ordinal, "ground_sign_direction" to 0)
        }
    }
    return blocks.find(sign.name, sign.properties + changes)
}

/**
 * The state of [block] once placed at [pos] against [face], by [placer],
 * who clicked [clickY] up the face. Blocks that turn follow the client's
 * own rules for the properties they share:
 *
 * - Pillars, like logs, lie along the face's axis.
 * - Slabs, stairs and trapdoors take the top half of a space when the
 *   player clicks a block's underside or the top half of its side.
 * - Stairs climb the way the player faces.
 * - Blocks with a front, like furnaces, chests and glazed terracotta, turn
 *   it to the player. Doors and fence gates face the way the player does,
 *   doors naming the side a quarter turn clockwise from it, trapdoors
 *   hinge on the side away from the player, and anvils lie across the way
 *   they face. Pistons, dispensers, droppers, barrels and crafters face
 *   the player from above or below too, as [Placer.facingFrom] says, and
 *   observers look away from them, and hoppers into the block they were
 *   placed against.
 * - Attachments, like buttons, levers, ladders, wall signs and tripwire
 *   hooks, point out of the face. A lever on a floor or ceiling lies the
 *   way the player faces. Torches have rules of their own, which [place]
 *   follows, as do lanterns, bells and hanging signs.
 * - Standing signs and banners turn to the player in sixteen steps.
 * - Leaves a player places never decay.
 *
 * Any other property keeps the item's value, as does a block whose rules
 * give a state that doesn't exist.
 */
fun BlockPalette.placedState(block: BlockState, pos: BlockPos, face: Face, clickY: Float, placer: Placer): BlockState =
    find(block.name, placedProperties(block, pos, face, clickY, placer)) ?: block

/** The properties [placedState] gives [block], whether or not the game has a state with all of them. */
internal fun placedProperties(block: BlockState, pos: BlockPos, face: Face, clickY: Float, placer: Placer): Map<String, Any> {
    val name = block.name
    val facing = placer.facing
    val top = face == Face.DOWN || (clickY > 0.5f && face != Face.UP)
    return block.properties.mapValues { (property, value) ->
        when (property) {
            "pillar_axis" -> face.axis
            VERTICAL_HALF -> if (top) "top" else "bottom"
            "upside_down_bit" -> bit(top)
            "weirdo_direction" -> STAIRS_CLIMB.indexOf(facing)
            CARDINAL -> when {
                name.endsWith("_door") -> facing.clockwise
                name.endsWith("fence_gate") || name == "minecraft:straw_bed" -> facing
                name.endsWith("anvil") -> facing.counterClockwise
                name == "minecraft:shelf_mushroom" -> face.takeIf { it.horizontal } ?: facing.opposite
                else -> facing.opposite
            }.id
            DIRECTION -> when {
                // A trapdoor numbers the way it faces, from its hinge out, as stairs number the ways they climb.
                name.endsWith("trapdoor") -> STAIRS_CLIMB.indexOf(facing.opposite)
                name == "minecraft:bed" -> facing.legacyIndex
                name == "minecraft:tripwire_hook" || name == "minecraft:cocoa" -> (face.takeIf { it.horizontal } ?: facing.opposite).legacyIndex
                name == "minecraft:grindstone" || name == "minecraft:bell" -> (if (face.horizontal) face else facing.opposite).legacyIndex
                else -> facing.opposite.legacyIndex
            }
            FACING_DIRECTION -> when {
                name.endsWith("glazed_terracotta") -> facing.opposite.ordinal
                name.endsWith("piston") -> placer.facingFrom(pos).storedReversed
                name in FACES_PLAYER -> placer.facingFrom(pos).ordinal
                name == "minecraft:hopper" -> (if (face.horizontal) face.opposite else Face.DOWN).ordinal
                name == "minecraft:end_rod" -> face.storedReversed
                name.endsWith("_stem") -> value
                else -> face.ordinal
            }
            "minecraft:facing_direction" -> placer.facingFrom(pos).opposite.id
            "orientation" -> crafterOrientation(placer.facingFrom(pos), facing)
            "minecraft:block_face" -> face.id
            LEVER_DIRECTION -> when (face) {
                Face.UP, Face.DOWN -> face.id + if (facing.axis == "x") "_east_west" else "_north_south"
                else -> face.id
            }
            "ground_sign_direction" -> placer.rotationTowards
            ATTACHMENT -> when (face) {
                Face.UP -> "standing"
                Face.DOWN -> "hanging"
                else -> "side"
            }
            HANGING -> bit(face == Face.DOWN)
            "persistent_bit" -> bit(true)
            RAIL_DIRECTION -> if (facing.axis == "x") RAIL_EAST_WEST else RAIL_NORTH_SOUTH
            "coral_direction" -> CORAL_WALL_ORDER.indexOf(face).takeIf { it >= 0 } ?: value
            // A fan on the floor lies across the way the player faces.
            "coral_fan_direction" -> if (facing.axis == "x") 1 else 0
            MULTI_FACE -> faceBits(MULTI_FACE_BITS, listOf(face.opposite))
            VINE_FACES -> faceBits(VINE_BITS, listOf(face.opposite))
            else -> value
        }
    }
}

/** Blocks that turn their front to the player, from above or below as well as around. */
private val FACES_PLAYER = setOf("minecraft:dispenser", "minecraft:dropper", "minecraft:barrel")

/**
 * This face as end rods and pistons store it: up and down as other blocks
 * do, but the four sides around the wrong way round, so that the number
 * for north means south.
 */
private val Face.storedReversed: Int get() = if (horizontal) opposite.ordinal else ordinal

/**
 * A crafter's orientation, named by its front and then its top: a crafter
 * facing a player beside it has its top up, and one facing up or down has
 * its top towards or away from the way the player faces.
 */
private fun crafterOrientation(front: Face, facing: Face): String {
    val top = when (front) {
        Face.DOWN -> facing.opposite
        Face.UP -> facing
        else -> Face.UP
    }
    return "${front.id}_${top.id}"
}

/** The sides coral fans on a wall face, in the order their `coral_direction` numbers them. */
private val CORAL_WALL_ORDER = listOf(Face.WEST, Face.EAST, Face.NORTH, Face.SOUTH)

/** The bit for each face in glow lichen's, sculk veins' and resin clumps' `multi_face_direction_bits`. */
internal val MULTI_FACE_BITS = mapOf(Face.DOWN to 1, Face.UP to 2, Face.SOUTH to 4, Face.WEST to 8, Face.NORTH to 16, Face.EAST to 32)

/** The bit for each side in vines' `vine_direction_bits`. */
internal val VINE_BITS = mapOf(Face.SOUTH to 1, Face.WEST to 2, Face.NORTH to 4, Face.EAST to 8)

internal fun faceBits(bits: Map<Face, Int>, faces: Collection<Face>) = faces.sumOf { bits[it] ?: 0 }

internal fun facesIn(bits: Map<Face, Int>, value: Int) = bits.filterValues { value and it != 0 }.keys

/**
 * [existing] with one more of [block] piled on: candles, sea pickles,
 * turtle eggs, petals, leaf litter and wildflowers, up to four, and snow
 * up to eight layers. Null if [block] doesn't pile onto [existing], or
 * there's no room. Snow piles only onto the top of snow.
 */
private fun BlockPalette.piledOnto(existing: BlockState, block: BlockState, face: Face): BlockState? {
    if (existing.name != block.name) return null
    val (property, more) = when {
        "candles" in existing.properties -> "candles" to (existing.properties["candles"] as Int + 1).takeIf { it <= 3 }
        "cluster_count" in existing.properties -> "cluster_count" to (existing.properties["cluster_count"] as Int + 1).takeIf { it <= 3 }
        existing.name == SNOW_LAYER -> {
            if (face != Face.UP) return null
            "height" to (existing.properties["height"] as Int + 1).takeIf { it <= 7 }
        }
        existing.name in PILES_BY_GROWTH -> "growth" to (existing.properties["growth"] as Int + 1).takeIf { it <= 3 }
        existing.name == "minecraft:turtle_egg" -> {
            val counts = listOf("one_egg", "two_egg", "three_egg", "four_egg")
            "turtle_egg_count" to counts.getOrNull(counts.indexOf(existing.properties["turtle_egg_count"]) + 1)
        }
        else -> return null
    }
    return find(existing.name, existing.properties + (property to (more ?: return null)))
}

/** Plants of which up to four lie in one space, counted by their `growth`. */
private val PILES_BY_GROWTH = setOf("minecraft:pink_petals", "minecraft:leaf_litter", "minecraft:wildflowers")

/**
 * [existing] glow lichen, sculk vein, resin clump or vines, spread over
 * one more face of its space: the one the player placed it against, which
 * the space's own block lies across [face]. Null if it covers that face
 * already, or isn't one of those.
 */
private fun BlockPalette.spreadOver(existing: BlockState, face: Face): BlockState? {
    val (property, bits) = when {
        MULTI_FACE in existing.properties -> MULTI_FACE to MULTI_FACE_BITS
        VINE_FACES in existing.properties -> VINE_FACES to VINE_BITS
        else -> return null
    }
    val value = existing.properties[property] as Int
    val bit = bits[face.opposite] ?: return null
    if (value and bit != 0) return null
    return find(existing.name, existing.properties + (property to (value or bit)))
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

internal const val SNOW_LAYER = "minecraft:snow_layer"

private val BlockState.isLantern get() = name.endsWith("lantern") && HANGING in properties

internal val BlockState.isChest get() = (name.endsWith("chest") && name != "minecraft:ender_chest")
