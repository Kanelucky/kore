package org.koremc.world

import org.koremc.registry.BlockState
import org.koremc.registry.ItemStack
import org.koremc.registry.maxStackSize
import org.koremc.registry.sameKindAs
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

/** How long an item lies in the world before it's gone, in ticks: five minutes. */
private const val ITEM_LIFETIME = 6000

/** How long an item that drops from a block waits before anyone can pick it up, in ticks. */
internal const val DROPPED_PICKUP_DELAY = 10

/** How far below the world an item falls before it's gone. */
private const val VOID_DEPTH = 64

// How items move each tick, as in the game: they fall, slow in the air, and slow more along the ground.
private const val GRAVITY = 0.04
private const val DRAG = 0.98
private const val GROUND_FRICTION = 0.6

/** Half an item's width, and its height: an item entity is a quarter of a block each way. */
private const val HALF_WIDTH = 0.125
private const val HEIGHT = 0.25

/** How far across an item reaches for others to merge with, beyond its own sides. */
private const val MERGE_REACH = 0.5

/** How often, in ticks of its age, an item on the ground looks for others to merge with: once a second. */
private const val MERGE_PERIOD = 20

/**
 * How close to a surface counts as touching it. Positions are kept as
 * floats, which near the overworld's floor are only good to about 1e-5,
 * and an item a hair inside a block mustn't fall through it.
 */
private const val TOUCHING = 1e-4

/**
 * An item lying in the world: a stack anyone can pick up once its pickup
 * delay has run out. [position] is the middle of its bottom, and
 * [velocity] is in blocks a tick.
 */
class ItemEntity(val id: Long, stack: ItemStack, position: Vec3, velocity: Vec3, pickupDelay: Int) {
    var stack = stack
        set(value) {
            field = value
            restacked = true
        }

    var position = position
        private set
    var velocity = velocity
        private set

    /** Ticks until it can be picked up. */
    var pickupDelay = pickupDelay
        private set

    var onGround = false
        private set

    /** Whether it moved in the last tick, so those who see it need to hear where it is now. */
    var moved = false
        private set

    /**
     * Whether its stack changed since the last tick began, by merging or
     * being partly picked up, so those who see it need to hear how many it
     * holds now.
     */
    var restacked = false
        internal set

    private var age = 0

    /**
     * Whether it can take in more items, or go into another stack: it's
     * landed, it isn't about to go, and its stack isn't full. Items in the
     * air pass each other by.
     */
    private val mergeable get() = onGround && age < ITEM_LIFETIME && stack.count < stack.item.maxStackSize

    internal fun tick(world: FlatWorld): Boolean {
        age++
        if (pickupDelay > 0) pickupDelay--
        if (age >= ITEM_LIFETIME || position.y < MIN_Y - VOID_DEPTH) return false

        var (vx, vy, vz) = velocity.toDoubles()
        vy -= GRAVITY
        val start = position
        var box = Box.around(position)
        val dy = world.clip(box, Axis.Y, vy)
        box = box.shifted(Axis.Y, dy)
        val dx = world.clip(box, Axis.X, vx)
        box = box.shifted(Axis.X, dx)
        val dz = world.clip(box, Axis.Z, vz)
        box = box.shifted(Axis.Z, dz)

        onGround = vy < 0 && dy != vy
        // Hitting something stops the item that way.
        if (dy != vy) vy = 0.0
        if (dx != vx) vx = 0.0
        if (dz != vz) vz = 0.0
        val friction = if (onGround) DRAG * GROUND_FRICTION else DRAG
        velocity = Vec3((vx * friction).toFloat(), (vy * DRAG).toFloat(), (vz * friction).toFloat())
        position = box.bottom()
        moved = position != start
        return true
    }

    /**
     * Once a second, merges this item with those of its kind close enough,
     * as the game does, until it goes into one of them: both must be on the
     * ground, and the side of one's box must come within [MERGE_REACH] of
     * the other's, at a height they share. Of two that merge, the smaller
     * stack goes into the larger, and into the other of two the same size.
     * They only merge when they fit in one stack. Returns the items that
     * went.
     */
    internal fun mergeWithNeighbours(world: FlatWorld): List<ItemEntity> {
        if (age % MERGE_PERIOD != 0 || !mergeable) return emptyList()
        val gone = mutableListOf<ItemEntity>()
        for (other in world.items) {
            if (other === this || !other.mergeable || !closeTo(other)) continue
            if (!other.stack.sameKindAs(stack) || other.stack.count + stack.count > stack.item.maxStackSize) continue
            if (other.stack.count < stack.count) {
                absorb(other)
                gone += other
            } else {
                other.absorb(this)
                gone += this
                break
            }
        }
        return gone
    }

    private fun closeTo(other: ItemEntity) =
        abs(other.position.x - position.x) < 2 * HALF_WIDTH + MERGE_REACH &&
            abs(other.position.z - position.z) < 2 * HALF_WIDTH + MERGE_REACH &&
            abs(other.position.y - position.y) < HEIGHT

    /** Takes in [other]'s items. The stack waits as long as either did to be picked up, and lasts as long as the newer. */
    private fun absorb(other: ItemEntity) {
        stack = stack.copy(count = stack.count + other.stack.count)
        pickupDelay = maxOf(pickupDelay, other.pickupDelay)
        age = minOf(age, other.age)
    }
}

/**
 * Drops [stacks] from the block at [pos], as a block breaking does: each
 * somewhere near the block's middle, popping up and out a little.
 */
fun FlatWorld.dropFromBlock(pos: BlockPos, stacks: List<ItemStack>) {
    for (stack in stacks) {
        fun jitter() = Random.nextDouble(-0.25, 0.25).toFloat()
        val at = Vec3(pos.x + 0.5f + jitter(), (pos.y + 0.5 - HEIGHT / 2).toFloat() + jitter(), pos.z + 0.5f + jitter())
        val velocity = Vec3(Random.nextDouble(-0.1, 0.1).toFloat(), 0.2f, Random.nextDouble(-0.1, 0.1).toFloat())
        spawnItem(stack, at, velocity, DROPPED_PICKUP_DELAY)
    }
}

/**
 * Moves every item along a tick: they fall, slide and land on blocks, and
 * merge with items of their kind close by. One that's lain for five
 * minutes, or fallen out of the world, is gone.
 */
fun FlatWorld.tickItems() {
    // Before any item moves, since one may merge into another that has yet to.
    items.forEach { it.restacked = false }
    for (item in items.toList()) {
        if (itemWith(item.id) == null) continue // Merged into another this tick
        if (!item.tick(this)) {
            removeItem(item.id)
            continue
        }
        item.mergeWithNeighbours(this).forEach { removeItem(it.id) }
    }
}

private enum class Axis { X, Y, Z }

/** A box in the world, from its lowest corner to its highest. */
private class Box(val min: DoubleArray, val max: DoubleArray) {
    fun shifted(axis: Axis, by: Double) = Box(min.copyOf().also { it[axis.ordinal] += by }, max.copyOf().also { it[axis.ordinal] += by })

    fun bottom() = Vec3(((min[0] + max[0]) / 2).toFloat(), min[1].toFloat(), ((min[2] + max[2]) / 2).toFloat())

    companion object {
        fun around(bottom: Vec3) = Box(
            doubleArrayOf(bottom.x - HALF_WIDTH, bottom.y.toDouble(), bottom.z - HALF_WIDTH),
            doubleArrayOf(bottom.x + HALF_WIDTH, bottom.y + HEIGHT, bottom.z + HALF_WIDTH),
        )
    }
}

/**
 * How far [box] can move along [axis] of the distance it wants to, [by],
 * before it meets a block's solid part.
 */
private fun FlatWorld.clip(box: Box, axis: Axis, by: Double): Double {
    if (by == 0.0) return 0.0
    val a = axis.ordinal
    val reach = Box(box.min.copyOf().also { it[a] += minOf(by, 0.0) }, box.max.copyOf().also { it[a] += maxOf(by, 0.0) })
    var allowed = by
    for (x in blocksAcross(reach, 0)) for (y in blocksAcross(reach, 1)) for (z in blocksAcross(reach, 2)) {
        val heights = stateAt(BlockPos(x, y, z))?.solidHeights() ?: continue
        val block = Box(doubleArrayOf(x.toDouble(), y + heights.start, z.toDouble()), doubleArrayOf(x + 1.0, y + heights.endInclusive, z + 1.0))
        // Only a block the box overlaps on the other two axes is in the way.
        if ((0..2).any { it != a && (box.max[it] <= block.min[it] + TOUCHING || box.min[it] >= block.max[it] - TOUCHING) }) continue
        if (by > 0 && box.max[a] <= block.min[a] + TOUCHING) allowed = minOf(allowed, block.min[a] - box.max[a])
        if (by < 0 && box.min[a] >= block.max[a] - TOUCHING) allowed = maxOf(allowed, block.max[a] - box.min[a])
    }
    // Rounding can leave a hair of a gap either way, which isn't worth moving across.
    return if (abs(allowed) < TOUCHING) 0.0 else allowed
}

private fun blocksAcross(box: Box, axis: Int) = floor(box.min[axis]).toInt()..floor(box.max[axis] - TOUCHING).toInt()

/**
 * The heights within a block that items land on and bump into, or null for
 * a block they fall through. Whole cubes fill the block, slabs fill their
 * half, and stairs at least the half they stand on. A big dripleaf's leaf
 * holds items up until it tips right over.
 */
private fun BlockState.solidHeights(): ClosedFloatingPointRange<Double>? = when {
    isFullCube -> 0.0..1.0
    name == BIG_DRIPLEAF -> leafHeights()
    properties["minecraft:vertical_half"] == "top" -> 0.5..1.0
    properties["minecraft:vertical_half"] == "bottom" -> 0.0..0.5
    "weirdo_direction" in properties -> if (properties["upside_down_bit"] == 1.toByte()) 0.5..1.0 else 0.0..0.5
    else -> null
}

private fun Vec3.toDoubles() = Triple(x.toDouble(), y.toDouble(), z.toDouble())
