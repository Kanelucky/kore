package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.registry.AIR
import org.koremc.registry.BlockState
import org.koremc.registry.ItemStack

/**
 * A block that broke, with the data it kept, like a bed's colour, which
 * what it drops can need, the items it held, and the experience a furnace
 * had stored.
 */
data class BrokenBlock(val state: BlockState, val blockEntity: NbtMap? = null, val contents: List<ItemStack?> = emptyList(), val experience: Float = 0f)

/** A sound a block makes as it changes, by the game's name for it, with its data: the network ID of the block making it, or -1. */
data class BlockSound(val pos: BlockPos, val sound: String, val data: Int)

/**
 * Something happening at a block that the client shows, like an anvil
 * clanging, by the game's number for it, with its data: at the block's
 * middle, or its corner, if not [centred], as the game shows plants
 * growing across their block from there.
 */
data class WorldEvent(val pos: BlockPos, val event: Int, val data: Int, val centred: Boolean = true)

/**
 * What a change to the world did around it: the blocks that broke, where
 * blocks changed shape, the sounds they made, where only the data a block
 * keeps changed, like a piston's arm moving out, where blocks a piston
 * moved were, other things that happened at blocks, the falling blocks
 * that couldn't land, where they came down, which drop as items, the
 * notes note blocks played, and the songs jukeboxes started or stopped.
 */
class Changes(
    val broken: Map<BlockPos, BrokenBlock>,
    val reshaped: List<BlockPos>,
    val sounds: List<BlockSound> = emptyList(),
    val data: List<BlockPos> = emptyList(),
    val moved: List<BlockPos> = emptyList(),
    val events: List<WorldEvent> = emptyList(),
    val dropped: List<Pair<Vec3, BlockState>> = emptyList(),
    val notes: List<NotePlayed> = emptyList(),
    val songs: List<SongChange> = emptyList(),
) {
    val isEmpty get() =
        broken.isEmpty() && reshaped.isEmpty() && sounds.isEmpty() && data.isEmpty() && events.isEmpty() && notes.isEmpty() && songs.isEmpty()
}

/** Why a block is to be looked at again, some ticks after something changed. */
enum class BlockUpdate {
    /** Whether it can still stay where it is, and for scaffolding, how far it now is from what holds it up. */
    SUPPORT,

    /** Whether coral out of water dies. */
    CORAL,

    /** The next step of a big dripleaf tipping over under what stands on it, or righting itself. */
    TILT,

    /** Whether a redstone torch lights or goes out, as the block it's on stops or starts being powered. */
    TORCH,

    /** Whether a repeater passes on power, or stops, as its back starts or stops being powered. */
    REPEATER,

    /** What a comparator gives out of its front, as what reaches it changes. */
    COMPARATOR,

    /** Whether a piston pushes out or pulls in, as power reaches it or goes. */
    PISTON,

    /** An observer's pulse starting, or ending. */
    OBSERVER,

    /** A redstone lamp going out, a moment after its power went. */
    LAMP,

    /** A pressed pressure plate looking again at what's on it. */
    PLATE,

    /** A lectern's pulse ending, two ticks after a page turned. */
    LECTERN,

    /** A pressed button popping back out. */
    BUTTON,

    /** Water or lava moving on. */
    FLOW,

    /** Whether sand and the like with nothing under it falls. */
    FALL,

    /** A full composter readying its bone meal. */
    COMPOST,

    /** A jukebox's song ending, which stops the power it gives. */
    SONG,

    /** A struck lightning rod stopping giving power. */
    ROD,
}

/**
 * What Kore looks at this block again for, once a tick it's due comes:
 * whether it can still stay, for a block something holds up, and what it
 * does by itself, for redstone and the like. The game saves only the
 * block a tick is due to, so this is what a saved tick was for. Empty for
 * a block Kore doesn't tick.
 */
internal val BlockState.dueFor: List<BlockUpdate>
    get() {
        val held = heldFrom != null || name == CACTUS || name == SCAFFOLDING || name == FARMLAND || MULTI_FACE in properties || VINE_FACES in properties
        val own = when {
            isRedstoneTorch -> BlockUpdate.TORCH
            isRepeater -> BlockUpdate.REPEATER
            isComparator -> BlockUpdate.COMPARATOR
            isPiston -> BlockUpdate.PISTON
            isObserver -> BlockUpdate.OBSERVER
            isLamp -> BlockUpdate.LAMP
            isPressurePlate -> BlockUpdate.PLATE
            isLectern -> BlockUpdate.LECTERN
            isLivingCoral -> BlockUpdate.CORAL
            name == BIG_DRIPLEAF -> BlockUpdate.TILT
            name.endsWith("button") -> BlockUpdate.BUTTON
            name == COMPOSTER -> BlockUpdate.COMPOST
            rodPowered -> BlockUpdate.ROD
            fallsFreely -> BlockUpdate.FALL
            liquid != null -> BlockUpdate.FLOW
            else -> null
        }
        return listOfNotNull(BlockUpdate.SUPPORT.takeIf { held }, own)
    }

/** Looks at the block at [pos] again for [update], [ticks] from now. */
internal fun FlatWorld.schedule(pos: BlockPos, ticks: Int, update: BlockUpdate) {
    scheduled.getOrPut(blockTicks + ticks) { LinkedHashSet() } += pos to update
}

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
    "big_dripleaf", "short_grass", "short_dry_grass", "tall_dry_grass", "fern", "deadbush", "bush", "firefly_bush", "dandelion", "poppy",
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
            (name.endsWith("lantern") && HANGING in properties) || isDripstone || name == "minecraft:mangrove_propagule" ->
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
 * any whole top, or a fence or wall. Crops, cane, cactus and the like
 * stand only on their own ground, as [standsOn] has it. Anything else
 * stands on, hangs from or leans on any block a placed block wouldn't
 * replace.
 */
internal fun FlatWorld.holdsUp(holderPos: BlockPos, face: Face, block: BlockState): Boolean {
    val holder = stateAt(holderPos) ?: return false
    if (face == Face.UP) standsOn(block, holder)?.let { return it }
    return when {
        TORCH_FACING in block.properties -> if (face == Face.UP) holder.holdsTorchOnTop else holder.hasFullFace(face)
        block.name.endsWith("button") || block.name == "minecraft:lever" || block.name == "minecraft:ladder" || block.isWire ||
            block.name.endsWith("repeater") || block.name.endsWith("comparator") -> holder.hasFullFace(face)
        else -> !holder.replaceable
    }
}

/**
 * Whether [state] can stay at [pos]: whatever holds it up is still there,
 * a cactus has no block beside it, and sugar cane has water by it.
 * Scaffolding stays within reach of what holds it, and vines and glow
 * lichen keep at least one face. Farmland can't stay farmland under a
 * whole block.
 */
internal fun FlatWorld.survives(pos: BlockPos, state: BlockState): Boolean {
    val holder = state.heldFrom
    if (holder != null && !holdsUp(pos.side(holder), holder.opposite, state)) return false
    if (state.name == CACTUS && AROUND.any { stateAt(pos.side(it))?.isFullCube == true }) return false
    if (state.name == REEDS && !caneWatered(pos)) return false
    if (state.name == SCAFFOLDING && scaffoldingDistance(pos) >= SCAFFOLDING_MAX_DISTANCE) return false
    if (state.name == FARMLAND && farmlandCovered(pos)) return false
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
    broken[pos] = BrokenBlock(state, blockEntity, emptyContainer(pos), takeExperience(pos))
    set(pos, blocks.stateOf(AIR))
    return blockEntity?.let { unpairChestFrom(it) }
}

/**
 * Brings the world around [changed] in line with it, and around each
 * block that changes in turn. Blocks shaped by their neighbours take
 * their new shape at once, like fences, walls, stairs, tripwire and
 * dripstone, and vines and glow lichen let go of faces with nothing
 * behind them. Blocks that can no longer stay where they are, like a
 * flower whose ground went, break a tick later, when [tickBlocks] looks
 * at them again, as scaffolding works out its distance again then: so a
 * stack of them falls one block a tick, as in the game. Farmland under a
 * whole block goes back to dirt then too. Leaves of a tree beside what
 * changed look again for their wood the next time a random tick lands
 * on them. Returns where blocks changed shape. Nothing breaks here.
 */
fun FlatWorld.settle(changed: Collection<BlockPos>): Changes {
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
        if (state.isLivingCoral) scheduleCoralCheck(at)
        // Breaking, and scaffolding's distance, wait a tick.
        if (!survives(at, state) || faces?.isEmpty() == true || (state.name == SCAFFOLDING && scaffoldingShaped(at, state) != state)) {
            schedule(at, 1, BlockUpdate.SUPPORT)
            continue
        }
        var shaped = joined(at, state)
        if (faces != null) shaped = withFaces(shaped, faces)
        if (shaped == state) continue
        set(at, shaped)
        reshaped += at
        around(at)
    }
    reshaped += wakeLeaves(changed + reshaped)
    return Changes(emptyMap(), reshaped.toList())
}

/**
 * Ticks the world's blocks: looks again at those whose time has come.
 * Blocks [settle] left that still can't stay break, with the other half of
 * a door, bed or tall plant, but for snow and hanging pointed dripstone,
 * which fall, and scaffolding takes its new distance. Sand and the like
 * with nothing under them fall, and falling blocks fall on, landing or
 * dropping as items. Coral out of water dies, big dripleaves tip over or
 * right themselves, pressed buttons pop back out, full composters ready
 * their bone meal, jukebox songs end, and cauldrons under water fill with
 * it. The world settles around what changed, which can leave more for
 * later ticks. Run once a tick. Returns what broke, where blocks changed
 * shape, the sounds they made, and the falling blocks that drop as items.
 */
fun FlatWorld.tickBlocks(): Changes {
    blockTicks++
    val reshaped = LinkedHashSet<BlockPos>()
    val data = LinkedHashSet<BlockPos>()
    // Arms already moving go on before any starts, which shows where it starts from first.
    movePistonArms(reshaped, data)
    val broken = linkedMapOf<BlockPos, BrokenBlock>()
    val sounds = mutableListOf<BlockSound>()
    val changed = mutableListOf<BlockPos>()
    // What changed since the last tick may make blocks fall, liquid flow, or lava and concrete powder harden.
    val since = blockChanges.toList().also { blockChanges.clear() }
    lookForFalls(since)
    changed += lookForFlows(since, sounds)
    val moved = mutableListOf<BlockPos>()
    val events = mutableListOf<WorldEvent>()
    val dropped = mutableListOf<Pair<Vec3, BlockState>>()
    // Those already falling go on before any more start.
    changed += moveFallingBlocks(sounds, events, dropped)
    for ((at, update) in scheduled.remove(blockTicks).orEmpty()) {
        // Liquid due to move isn't any more, whatever's there now, so liquid there later can be.
        if (update == BlockUpdate.FLOW) flowDue -= at
        val state = stateAt(at)?.takeIf { it.name != AIR } ?: continue
        when (update) {
            BlockUpdate.SUPPORT -> if (!survives(at, state) || remainingFaces(at, state)?.isEmpty() == true) {
                if (state.name == FARMLAND) {
                    set(at, blocks.defaultOf(DIRT) ?: continue)
                    changed += at
                    continue
                }
                if (state.fallsUnheld) {
                    changed += fallFrom(at, state)
                    continue
                }
                breakAt(at, state, broken)?.let(reshaped::add)
                changed += at
                state.otherHalf(at)?.let { other -> stateAt(other)?.takeIf { it.name == state.name }?.let { breakAt(other, it, broken); changed += other } }
            } else if (state.name == SCAFFOLDING) {
                val shaped = scaffoldingShaped(at, state)
                if (shaped == state) continue
                set(at, shaped)
                reshaped += at
                changed += at
            }
            BlockUpdate.CORAL -> coralDies(at, state)?.let { sound ->
                sounds += sound
                reshaped += at
                changed += at
            }
            BlockUpdate.TILT -> tiltOn(at, state)?.let { sound ->
                sounds += sound
                reshaped += at
            }
            BlockUpdate.TORCH -> if (tickTorch(at, state, sounds)) reshaped += at
            BlockUpdate.REPEATER -> if (tickRepeater(at, state)) reshaped += at
            BlockUpdate.COMPARATOR -> if (tickComparator(at, state)) reshaped += at
            BlockUpdate.PISTON -> movePiston(at, state, broken, reshaped, sounds, moved)
            BlockUpdate.OBSERVER -> if (tickObserver(at, state)) reshaped += at
            BlockUpdate.LAMP -> if (tickLamp(at, state)) reshaped += at
            BlockUpdate.PLATE -> if (checkPlate(at, state, sounds)) reshaped += at
            BlockUpdate.LECTERN -> if (endPagePulse(at, state)) reshaped += at
            BlockUpdate.BUTTON -> if (releaseButton(at, sounds)) reshaped += at
            BlockUpdate.FLOW -> changed += flow(at, broken, sounds)
            BlockUpdate.FALL -> if (canFall(at, state)) {
                startFalling(at, state)
                changed += at
            }
            BlockUpdate.COMPOST -> if (readyCompost(at, state, sounds)) reshaped += at
            BlockUpdate.SONG -> endSong(at)
            BlockUpdate.ROD -> if (endRodPower(at, state)) reshaped += at
        }
    }
    changed += fillFromAbove(since, events)
    reshaped += changed
    reshaped += settle(changed).reshaped
    reshaped.removeAll(broken.keys)
    return Changes(broken, reshaped.toList(), sounds, (data - reshaped).toList(), moved, events, dropped)
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
