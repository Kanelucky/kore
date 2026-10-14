package org.koremc.world

import org.koremc.registry.AIR
import org.koremc.registry.BlockState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val PILLAR_AXIS = "pillar_axis"
internal const val VINE = "minecraft:vine"
private const val PODZOL = "minecraft:podzol"
private const val SHELF_MUSHROOM = "minecraft:shelf_mushroom"
private const val RED_SHRUB = "minecraft:red_shrub"

/** Plants a tree grows through as if they were air: grass and ferns, short or tall. */
private val GRASSES = setOf("minecraft:short_grass", "minecraft:fern", "minecraft:tall_grass", "minecraft:large_fern")

/** Leaves of any kind, placed by a player or grown on a tree. */
internal val BlockState.isLeaves get() = LEAF_UPDATE in properties && PERSISTENT in properties

/** A sapling of a tree, of any kind but bamboo's. */
internal val BlockState.isSapling get() = name.endsWith("_sapling") && name != BAMBOO_SAPLING

/** The four ways a tree leans or branches, in the order the game numbers them: south, west, north and east. */
private val LEANS = listOf(Face.SOUTH, Face.WEST, Face.NORTH, Face.EAST)

/**
 * A tree growing in [world], from a sapling or the like, as the game grows
 * it: the blocks it puts there, as it puts them, by the rules of what a
 * tree grows through and what it takes the place of. Chance comes from
 * [random].
 */
internal class Tree(val world: FlatWorld, val random: Random) {
    /** Where the tree put blocks, in the order it put them. */
    val changed = LinkedHashSet<BlockPos>()

    fun stateAt(pos: BlockPos): BlockState? = if (pos.y in MIN_Y..MAX_Y) world.stateAt(pos) else null

    fun nameAt(pos: BlockPos): String? = stateAt(pos)?.name

    /** Whether a tree grows on the block at [pos]: dirt of any kind, moss or mud. */
    fun soil(pos: BlockPos) = nameAt(pos) in DIRTS

    /** Whether a tree has room at [pos], checking before it grows: air, leaves, vines, saplings, grass, or the ground it grows from. */
    fun hasRoom(pos: BlockPos): Boolean {
        val state = stateAt(pos) ?: return false
        return state.name == AIR || state.isLeaves || state.name == VINE || state.isSapling || state.name in GRASSES || state.name in DIRTS
    }

    /** Whether a tree's logs, and most of its leaves, take the place of what's at [pos]: what a placed block would, or leaves, vines or saplings. */
    fun replaceable(pos: BlockPos): Boolean {
        val state = stateAt(pos) ?: return false
        return state.replaceable || state.isLeaves || state.name == VINE || state.isSapling
    }

    /** Whether a canopy grows into [pos]: air or leaves, and nothing else. */
    fun leafFits(pos: BlockPos) = stateAt(pos)?.let { it.name == AIR || it.isLeaves } == true

    fun isAir(pos: BlockPos) = nameAt(pos) == AIR

    fun put(pos: BlockPos, state: BlockState) {
        world[pos] = state
        changed += pos
    }

    /** Puts [name], in its default state but for [properties], at [pos], if the game has it. */
    fun put(pos: BlockPos, name: String, vararg properties: Pair<String, Any>) {
        val default = world.blocks.defaultOf(name) ?: return
        put(pos, world.blocks.find(name, default.properties + properties) ?: return)
    }

    /** Puts a log of [wood] along [axis] at [pos], if it can go there. Returns whether it did. */
    fun log(pos: BlockPos, wood: String, axis: String = "y"): Boolean {
        if (!replaceable(pos)) return false
        put(pos, wood, PILLAR_AXIS to axis)
        return true
    }

    /** Puts [leaves] at [pos], if [fits] says they go there. Returns whether they did. */
    fun leaf(pos: BlockPos, leaves: String, fits: (BlockPos) -> Boolean = ::leafFits): Boolean {
        if (!fits(pos)) return false
        put(pos, leaves)
        return true
    }

    /** Whether a chance of [percent] in a hundred comes up. */
    fun chance(percent: Double) = percent >= 100 || (percent > 0 && percent > random.nextDouble() * 100)

    /** A whole number in [range], picked at random. */
    fun draw(range: IntRange) = random.nextInt(range.first, range.last + 1)

    /**
     * Hangs vines, each [percent] of the time, off the [sides] of the block at
     * [pos], into the air beside it, and down from there, [steps] blocks at
     * most, stopping at the first block in the way.
     */
    fun hangVines(pos: BlockPos, percent: Double, steps: Int, sides: Collection<Face> = AROUND) {
        if (percent <= 0 || steps <= 0) return
        for (side in listOf(Face.WEST, Face.EAST, Face.NORTH, Face.SOUTH)) {
            if (side !in sides || !chance(percent)) continue
            var at = pos.side(side)
            if (!isAir(at)) continue
            // The vine holds on to the side it hangs from.
            val vine = world.blocks.find(VINE, mapOf(VINE_FACES to faceBits(VINE_BITS, listOf(side.opposite)))) ?: return
            for (step in 0 until steps) {
                put(at, vine)
                at = at.side(Face.DOWN)
                if (!isAir(at)) break
            }
        }
    }

    /** Turns the grass, farmland or mycelium under a trunk at [pos] to dirt. */
    fun dirtUnder(pos: BlockPos) {
        if (nameAt(pos.side(Face.DOWN)) in setOf(GRASS_BLOCK, FARMLAND, MYCELIUM)) put(pos.side(Face.DOWN), DIRT)
    }

    /**
     * Whether a tree [height] tall has room above [pos], on ground it grows
     * on: the trunk's own column at the bottom, a block around it on the way
     * up, and two around its top.
     */
    fun hasRoomFor(pos: BlockPos, height: Int): Boolean {
        if (!soil(pos.side(Face.DOWN))) return false
        for (y in pos.y..pos.y + height + 1) {
            val reach = when {
                y >= pos.y + height - 1 -> 2
                y == pos.y -> 0
                else -> 1
            }
            for (x in pos.x - reach..pos.x + reach) for (z in pos.z - reach..pos.z + reach) if (!hasRoom(BlockPos(x, y, z))) return false
        }
        return true
    }
}

/** The leaves a tree carries on its trunk, put round where the trunk carries them, the trunk [width] blocks across. */
private interface Canopy {
    fun Tree.place(at: BlockPos, width: Int, leaves: String)
}

/** A whole tree: its trunk, which carries its canopy, [height] tall, of [wood], with [leaves]. Returns whether it grew. */
private interface Trunk {
    fun height(random: Random): Int

    fun Tree.place(pos: BlockPos, height: Int, wood: String, leaves: String): Boolean
}

private fun Canopy.putOn(tree: Tree, at: BlockPos, width: Int, leaves: String) = with(tree) { place(at, width, leaves) }

/**
 * The canopy of an oak, birch or jungle tree: a stack of squares from
 * [rows] below where it sits to its top, narrowing upward as [slopeRise]
 * has it, each with its corners left out by the chance [holes] gives that
 * row, from the lowest up. A jungle tree's hang vines.
 */
private class BlobCanopy(
    val rows: IntRange,
    val minWidth: Int,
    val slopeRun: Int,
    val slopeRise: Double,
    val holes: List<Double>,
    val vinePercent: Double = 0.0,
    val vineSteps: Int = 0,
) : Canopy {
    private fun slope(offset: Int) = (offset * slopeRun * slopeRise).toInt()

    private fun radius(offset: Int) = minWidth + slope(rows.last) - slope(offset)

    override fun Tree.place(at: BlockPos, width: Int, leaves: String) {
        for (offset in rows) {
            val r = radius(offset)
            val hole = holes[min(offset - rows.first, holes.lastIndex)]
            for (dx in -r..r) for (dz in -r..r) {
                // Only a row's four corners are ever left out, each by its own chance.
                if (abs(dx) == r && abs(dz) == r && chance(hole)) continue
                leaf(BlockPos(at.x + dx, at.y + offset, at.z + dz), leaves)
            }
        }
        if (vinePercent <= 0) return
        // Vines hang off the leaves once they're all there.
        for (offset in rows) {
            val r = radius(offset)
            for (dx in -r..r) for (dz in -r..r) {
                val leaf = BlockPos(at.x + dx, at.y + offset, at.z + dz)
                if (nameAt(leaf) == leaves) hangVines(leaf, vinePercent, vineSteps)
            }
        }
    }
}

/**
 * A spruce's canopy: from a little above the trunk's top down to near the
 * ground, rings widening and narrowing again by turns, up to [widest]
 * across.
 */
private class SpruceCanopy(val lowerOffset: IntRange, val upperOffset: IntRange, val widest: IntRange) : Canopy {
    override fun Tree.place(at: BlockPos, width: Int, leaves: String) {
        // It reaches down near the ground the tree grew from, so that's found first.
        var ground = at.y
        while (!soil(BlockPos(at.x, ground - 1, at.z))) if (--ground <= MIN_Y) return
        val lower = draw(lowerOffset)
        val upper = draw(upperOffset)
        val most = draw(widest)
        val spread = widest.last + 1 - widest.first
        var radius = random.nextInt(max(1, spread))
        var reach = spread - 1
        var narrowest = 0
        val top = at.y + upper
        for (row in 0..top - (ground + lower)) {
            val y = top - row
            for (dx in -radius..radius) for (dz in -radius..radius) {
                if (radius != 0 && abs(dx) == radius && abs(dz) == radius) continue
                leaf(BlockPos(at.x + dx, y, at.z + dz), leaves)
            }
            if (reach <= radius) {
                radius = narrowest
                narrowest = 1
                reach = min(reach + 1, most)
                continue
            }
            radius++
        }
    }
}

/**
 * The canopy of a dark oak or pale oak: a slab of leaves over a trunk
 * [coreWidth] across, wider between its rims, which droop at the corners,
 * and now and then a cap over the trunk.
 */
private class RoofedCanopy(val height: Int, val coreWidth: Int, val outerRadius: Int, val innerRadius: Int) : Canopy {
    private val layers get() = max(1, height - 3)

    override fun Tree.place(at: BlockPos, width: Int, leaves: String) {
        val size = if (width > 0) width else coreWidth
        var r = outerRadius
        for (dx in -r..size - 1 + r) for (dz in -r..size - 1 + r) {
            leaf(BlockPos(at.x + dx, at.y - 1, at.z + dz), leaves, ::isAir)
            // The rim above has three cells cut from each corner, counted out from the trunk.
            val outX = maxOf(-dx, dx - size + 1, 0)
            val outZ = maxOf(-dz, dz - size + 1, 0)
            if (outX + outZ >= r * 2 - 1) continue
            leaf(BlockPos(at.x + dx, at.y + layers, at.z + dz), leaves, ::isAir)
        }
        r = innerRadius
        for (y in 0 until layers) for (dx in -r until size + r) for (dz in -r until size + r) {
            if (abs(dx) >= r && abs(dz) >= r) continue
            leaf(BlockPos(at.x + dx, at.y + y, at.z + dz), leaves, ::isAir)
        }
        if (random.nextInt(2) != 0) return
        for (dx in 0 until size) for (dz in 0 until size) leaf(BlockPos(at.x + dx, at.y + layers + 1, at.z + dz), leaves, ::isAir)
    }
}

/** An acacia's canopy, at its top and the end of its branch: a wide ring, [size] across, and a smaller one over it. */
private class AcaciaCanopy(val size: Int, val simplified: Boolean = false) : Canopy {
    override fun Tree.place(at: BlockPos, width: Int, leaves: String) {
        val m = max(1, size)
        if (simplified) {
            for (dx in 1 - m until m) for (dz in 1 - m until m) leaf(BlockPos(at.x + dx, at.y + 1, at.z + dz), leaves)
        } else {
            val k = max(2, size)
            for (dx in 2 - k until k - 1) for (dz in 2 - k until k - 1) leaf(BlockPos(at.x + dx, at.y + 1, at.z + dz), leaves)
            for ((dx, dz) in listOf(m - 1 to 0, 1 - m to 0, 0 to m - 1, 0 to 1 - m)) leaf(BlockPos(at.x + dx, at.y + 1, at.z + dz), leaves)
        }
        for (dx in -size..size) for (dz in -size..size) {
            if (abs(dx) == size && abs(dz) == size) continue
            leaf(BlockPos(at.x + dx, at.y, at.z + dz), leaves)
        }
    }
}

/**
 * A round row of leaves [r] out around a trunk [sizeX] by [sizeZ] across,
 * keeping a cell just outside the circle when the one towards the trunk
 * is inside it.
 */
private fun Tree.leafRow(at: BlockPos, r: Int, sizeX: Int, sizeZ: Int, leaves: String) {
    for (dx in -r until sizeX + r) for (dz in -r until sizeZ + r) {
        fun outside(x: Int, z: Int) = x * x + z * z > r * r
        if (outside(dx, dz) && outside(dx - 1, dz - 1) && outside(dx, dz - 1) && outside(dx - 1, dz)) continue
        leaf(BlockPos(at.x + dx, at.y, at.z + dz), leaves, ::replaceable)
    }
}

/** A mega jungle tree's canopy, widening on the way down, [height] rows of it. */
private class MegaCanopy(val height: IntRange, val baseRadius: Int, val coreWidth: Int, val simplified: Boolean = false) : Canopy {
    override fun Tree.place(at: BlockPos, width: Int, leaves: String) {
        val size = if (simplified || width <= 0) coreWidth else width
        val rows = draw(height)
        for (row in rows - 1 downTo 0) leafRow(BlockPos(at.x, at.y - row, at.z), coreWidth / 2 + baseRadius + row, size, size, leaves)
    }
}

/** A mega spruce's or mega pine's canopy: a cone, [height] rows of it, widening down, and frilled every other row. */
private class MegaPineCanopy(val height: IntRange, val baseRadius: Int, val radiusStep: Double, val coreWidth: Int) : Canopy {
    override fun Tree.place(at: BlockPos, width: Int, leaves: String) {
        val size = if (width > 0) width else coreWidth
        val rows = draw(height)
        var last = 0
        for (down in rows downTo 0) {
            val y = at.y - down
            val r = baseRadius + floor(down.toDouble() / rows * radiusStep).toInt()
            val frill = if (down >= 1 && r == last && Math.floorMod(y, 2) == 0) r + 1 else r
            leafRow(BlockPos(at.x, y, at.z), frill, size, size, leaves)
            last = r
        }
    }
}

/**
 * A cherry's crown: layers of leaves, the widest in the middle, with
 * corners and the bottom ring's edge left out by chance, and leaves
 * hanging from the two lowest layers' edges.
 */
private class CherryCanopy(
    val height: Int,
    val radius: Int,
    val coreWidth: Int,
    val wideBottomHole: Double,
    val cornerHole: Double,
    val hanging: Double,
    val hangingFurther: Double,
) : Canopy {
    override fun Tree.place(at: BlockPos, width: Int, leaves: String) {
        // From the top down, the two layers under the middle hanging leaves off their edges.
        val widest = coreWidth + radius - 2
        layer(at, height - 3, widest - 2, leaves)
        layer(at, height - 4, widest - 1, leaves)
        for (dy in height - 5 downTo 0) layer(at, dy, widest, leaves)
        for ((dy, r) in listOf(-1 to widest, -2 to widest - 1)) {
            layer(at, dy, r, leaves)
            hang(at, dy, r, leaves)
        }
    }

    private fun Tree.layer(at: BlockPos, dy: Int, r: Int, leaves: String) {
        val w = coreWidth
        for (dx in -r until w + r) for (dz in -r until w + r) {
            var ax = abs(dx)
            var az = abs(dz)
            if (w >= 2) {
                ax = min(ax, abs(dx - (w - 1)))
                az = min(az, abs(dz - (w - 1)))
            }
            // The layer under the middle has its whole outer ring left out by chance, first.
            if (dy == -1 && (ax == r || az == r) && chance(wideBottomHole)) continue
            val corner = ax == r && az == r
            if ((corner || (r >= 3 && ax + az > 2 * r - 2)) && chance(cornerHole)) continue
            leaf(BlockPos(at.x + dx, at.y + dy, at.z + dz), leaves)
        }
    }

    /** Hangs leaves under the edges of the layer [dy] down, edge by edge, so that each corner's only tried once. */
    private fun Tree.hang(at: BlockPos, dy: Int, r: Int, leaves: String) {
        val e = coreWidth + r - 1
        val edges = listOf<(Int) -> Pair<Int, Int>>({ e to it }, { -it to e }, { -r to -it }, { it to -r })
        for (edge in edges) for (t in -r until e) {
            val (dx, dz) = edge(t)
            val above = BlockPos(at.x + dx, at.y + dy, at.z + dz)
            if (nameAt(above) != leaves || !chance(hanging)) continue
            // The leaf under the edge has to go down before the one under that is tried.
            val below = above.side(Face.DOWN)
            if (leaf(below, leaves) && chance(hangingFurther)) leaf(below.side(Face.DOWN), leaves)
        }
    }
}

/** A straight trunk a block across, as oaks, birches, jungle trees and spruces have, [trunkHeight] tall less a little for some. */
private class StraightTrunk(
    val trunkHeight: IntRange,
    val canopy: Canopy,
    val heightModifier: IntRange = 0..0,
    val vinePercent: Double = 0.0,
) : Trunk {
    override fun height(random: Random) = random.nextInt(trunkHeight.first, trunkHeight.last + 1)

    override fun Tree.place(pos: BlockPos, height: Int, wood: String, leaves: String): Boolean {
        if (!hasRoomFor(pos, height)) return false
        var top: BlockPos? = null
        for (i in 0 until height + draw(heightModifier)) {
            val at = BlockPos(pos.x, pos.y + i, pos.z)
            // A block the trunk can't take leaves a gap in it, rather than ending the tree.
            if (!log(at, wood)) continue
            hangVines(at, vinePercent, 1)
            top = at
        }
        top ?: return false
        canopy.putOn(this, top.side(Face.UP), 1, leaves)
        dirtUnder(pos)
        return true
    }
}

/**
 * The trunk of an acacia, which leans near its top and carries a branch,
 * or of a dark oak or pale oak, [width] across, which stays upright and
 * now and then carries short branches round it. Each branch carries a
 * canopy of its own.
 */
private class BranchingTrunk(
    val base: Int,
    val extra: List<Int>,
    val width: Int,
    val canopyFrom: Int,
    val leanHeight: IntRange,
    val leanSteps: IntRange,
    val leans: Boolean,
    val branchLength: IntRange,
    val branchPosition: IntRange,
    val branchPercent: Double,
    val branchCanopy: Canopy,
    val canopy: Canopy,
) : Trunk {
    override fun height(random: Random) = base + extra.sumOf { random.nextInt(it) }

    override fun Tree.place(pos: BlockPos, height: Int, wood: String, leaves: String): Boolean {
        if (!hasRoomFor(pos, height)) return false
        for (dx in 0 until width) for (dz in 0 until width) {
            val below = BlockPos(pos.x + dx, pos.y - 1, pos.z + dz)
            if (nameAt(below) != DIRT) put(below, DIRT)
        }
        val lean = LEANS[random.nextInt(LEANS.size)]
        val leanStart = height - draw(leanHeight)
        var steps = draw(leanSteps)
        var x = pos.x
        var z = pos.z
        var anchor: BlockPos? = null
        for (i in 0 until height) {
            if (leans && i >= leanStart && steps > 0) {
                x += lean.dx
                z += lean.dz
                steps--
            }
            for (dx in 0 until width) for (dz in 0 until width) {
                val at = BlockPos(x + dx, pos.y + i, z + dz)
                if (log(at, wood) && i >= canopyFrom) anchor = at
            }
        }
        anchor ?: return true
        canopy.putOn(this, anchor, width, leaves)
        branch(pos, anchor, lean, height, leanStart, wood, leaves)
        return true
    }

    private fun Tree.branch(pos: BlockPos, anchor: BlockPos, lean: Face, height: Int, leanStart: Int, wood: String, leaves: String) {
        if (leans) {
            // A leaning trunk grows a single branch, the other way from its lean, from the column it grew from.
            val way = LEANS[random.nextInt(LEANS.size)]
            if (way == lean || !chance(branchPercent)) return
            val position = draw(branchPosition)
            var length = draw(branchLength)
            var at = BlockPos(pos.x, anchor.y, pos.z)
            var y = leanStart - position
            while (y < height && length > 0) {
                if (y >= 1) {
                    at = BlockPos(at.x + way.dx, pos.y + y, at.z + way.dz)
                    log(at, wood)
                }
                y++
                length--
            }
            branchCanopy.putOn(this, at, 1, leaves)
            return
        }
        for (dx in -1..width) for (dz in -1..width) {
            if (dx in 0 until width && dz in 0 until width) continue
            if (!chance(branchPercent)) continue
            val length = draw(branchLength)
            val position = draw(branchPosition)
            for (k in 0 until length) log(BlockPos(pos.x + dx, anchor.y - k - position, pos.z + dz), wood)
            // The canopy hangs off the anchor, a trunk's width from the branch's logs.
            branchCanopy.putOn(this, BlockPos(anchor.x + dx, anchor.y - position, anchor.z + dz), 1, leaves)
        }
    }
}

/**
 * Podzol spread round the base of a mega spruce or pine: a patch at each
 * corner of the trunk, and [clusters] more round its edge.
 */
private class PodzolPatches(val clusters: Int, val radius: Int) {
    fun Tree.spread(pos: BlockPos, width: Int) {
        if (clusters <= 0) return
        for ((dx, dz) in listOf(-1 to -1, width to -1, -1 to width, width to width)) patch(BlockPos(pos.x + dx, pos.y, pos.z + dz))
        repeat(clusters) {
            // Only the edge of an eight by eight square around the trunk takes them: a pick inside it spreads none.
            val n = random.nextInt(64)
            val q = n % 8
            if (q == 0 || q == 7 || n < 8 || n >= 56) patch(BlockPos(pos.x - 3 + q, pos.y, pos.z - 3 + n / 8))
        }
    }

    private fun Tree.patch(at: BlockPos) {
        for (dx in -radius..radius) for (dz in -radius..radius) {
            if (abs(dx) == radius && abs(dz) == radius) continue
            // Down each column for the first block it can take over.
            for (dy in 2 downTo -3) {
                val pos = BlockPos(at.x + dx, at.y - 1 + dy, at.z + dz)
                when (nameAt(pos)) {
                    PODZOL -> break
                    GRASS_BLOCK, DIRT -> {
                        put(pos, PODZOL)
                        break
                    }
                }
            }
        }
    }
}

/**
 * The trunk of a mega spruce, pine or jungle tree, two blocks across,
 * grown from four saplings in a square: the canopy and any branches go
 * first, so the trunk takes the place of leaves it runs into, then the
 * ground under it turns to [ground], and a spruce spreads podzol round it.
 */
private class MegaTrunk(
    val base: Int,
    val extra: List<Int>,
    val canopy: Canopy,
    val ground: String,
    val patches: PodzolPatches? = null,
    val branchLength: Int = 0,
    val branchInterval: IntRange = 0..0,
    val branchSlope: Double = 0.0,
    val branchCanopy: Canopy? = null,
    val vinePercent: Double = 0.0,
) : Trunk {
    private val width = 2

    override fun height(random: Random) = base + extra.sumOf { random.nextInt(it) }

    override fun Tree.place(pos: BlockPos, height: Int, wood: String, leaves: String): Boolean {
        if (!soil(pos.side(Face.DOWN))) return false
        // Its own square at the bottom, and a block round it the rest of the way up.
        for (i in 0..height + 1) {
            val r = if (i == 0) width - 1 else width
            for (dx in -r..r) for (dz in -r..r) if (!hasRoom(BlockPos(pos.x + dx, pos.y + i, pos.z + dz))) return false
        }
        canopy.putOn(this, BlockPos(pos.x, pos.y + height, pos.z), width, leaves)
        branch(pos, height, wood, leaves)
        for (i in 0 until height) for (dx in 0 until width) for (dz in 0 until width) {
            val at = BlockPos(pos.x + dx, pos.y + i, pos.z + dz)
            if (!log(at, wood)) continue
            val outside = listOfNotNull(Face.WEST.takeIf { dx == 0 }, Face.EAST.takeIf { dx == width - 1 }, Face.NORTH.takeIf { dz == 0 }, Face.SOUTH.takeIf { dz == width - 1 })
            hangVines(at, vinePercent, 1, outside)
        }
        for (dx in 0 until width) for (dz in 0 until width) {
            val below = BlockPos(pos.x + dx, pos.y - 1, pos.z + dz)
            if (nameAt(below) != ground) put(below, ground)
        }
        patches?.run { spread(pos, width) }
        return true
    }

    /** The branches of a mega jungle tree, off its upper half, each ending in a canopy of its own. */
    private fun Tree.branch(pos: BlockPos, height: Int, wood: String, leaves: String) {
        if (branchLength <= 0) return
        var y = pos.y - draw(branchInterval) + height
        while (y > pos.y + height / 2) {
            val angle = random.nextDouble() * 2 * PI
            var bx = pos.x + 1
            var bz = pos.z + 1
            val start = (y - 1) - (branchLength - 1) * branchSlope
            for (b in 0 until branchLength) {
                bx = pos.x + (1.5 + cos(angle) * b).toInt()
                bz = pos.z + (1.5 + sin(angle) * b).toInt()
                log(BlockPos(bx, (start + b * branchSlope).toInt(), bz), wood)
            }
            branchCanopy?.putOn(this, BlockPos(bx, y, bz), 1, leaves)
            y -= draw(branchInterval)
        }
    }
}

/**
 * A cherry's trunk: a branch, two, or two and a crown at the top, each as
 * often, each branch reaching out and up to a crown of its own.
 */
private class CherryTrunk(val canopy: Canopy) : Trunk {
    override fun height(random: Random) = 7 + random.nextInt(2)

    override fun Tree.place(pos: BlockPos, height: Int, wood: String, leaves: String): Boolean {
        if (!hasRoomFor(pos, height)) return false
        val shape = random.nextInt(3)
        val start = max(0, draw(-4..-3) + height - 1)
        var end = max(0, height - 5)
        if (start <= end) end++
        val way = LEANS[random.nextInt(LEANS.size)]
        val logs = when (shape) {
            0 -> start + 1
            1 -> max(start, end) + 1
            else -> height
        }
        if (nameAt(pos.side(Face.DOWN)) != DIRT && soil(pos.side(Face.DOWN))) put(pos.side(Face.DOWN), DIRT)
        // A block the trunk can't take leaves a gap in it.
        for (i in 0 until logs) log(BlockPos(pos.x, pos.y + i, pos.z), wood)
        // Both branches grow before any crown does, so a crown gives way to the logs of the branch beside it.
        val crowns = mutableListOf<BlockPos>()
        if (shape == 2) crowns += BlockPos(pos.x, pos.y + height, pos.z)
        crowns += branch(pos, way, height, start, logs, wood)
        if (shape >= 1) crowns += branch(pos, way.opposite, height, end, logs, wood)
        for (crown in crowns) canopy.putOn(this, crown, 1, leaves)
        return true
    }

    /** Grows a branch out of the trunk, [at] blocks up, towards [way], and returns where its crown goes. */
    private fun Tree.branch(pos: BlockPos, way: Face, height: Int, at: Int, logs: Int, wood: String): BlockPos {
        val rise = height - 1 + draw(-1..0)
        // A branch is longer when it ends below where it left the trunk, or leaves it below its top log.
        val long = at < logs - 1 || rise < at
        val length = draw(2..4) + if (long) 1 else 0
        val axis = if (way.dz != 0) "z" else "x"
        var x = pos.x
        var y = pos.y + at
        var z = pos.z
        repeat(if (long) 2 else 1) {
            x += way.dx
            z += way.dz
            log(BlockPos(x, y, z), wood, axis)
        }
        // It walks to its end, each step up as often as the height left is of the distance left.
        val tx = pos.x + way.dx * length
        val ty = pos.y + rise
        val tz = pos.z + way.dz * length
        while (x != tx || y != ty || z != tz) {
            val dx = tx - x
            val dy = ty - y
            val dz = tz - z
            if (random.nextDouble() < abs(dy).toDouble() / (abs(dx) + abs(dy) + abs(dz))) {
                y += dy.sign
                log(BlockPos(x, y, z), wood)
                continue
            }
            if (abs(dx) >= abs(dz)) x += dx.sign else z += dz.sign
            log(BlockPos(x, y, z), wood, axis)
        }
        return BlockPos(tx, ty + 1, tz)
    }
}

/** Whether the cell [x], [z] of a poplar's canopy is in the pair of opposite quarters each layer reaches one further into. */
private fun fullerQuarter(x: Int, z: Int, sameSign: Boolean) =
    if (sameSign) (x < 0 && z < 0) || (x > 0 && z > 0) else (x < 0 && z > 0) || (x > 0 && z < 0)

/**
 * A poplar: a straight trunk, now and then with shelf mushrooms on it, a
 * few short logs propping its canopy, and the canopy itself, a stack of
 * diamond-like layers with a cross of logs through the widest.
 */
private class PoplarTrunk : Trunk {
    override fun height(random: Random) = random.nextInt(7, 12)

    override fun Tree.place(pos: BlockPos, height: Int, wood: String, leaves: String): Boolean {
        if (!hasRoomFor(pos, height)) return false
        val remaining = 4
        for (y in 0 until height) {
            val at = BlockPos(pos.x, pos.y + y, pos.z)
            if (log(at, wood)) shelfMushroom(at)
        }
        // The props take the first of the four ways, shuffled.
        val offset = height - remaining
        for (way in LEANS.shuffled(random).take(min(draw(1..4), LEANS.size))) {
            log(BlockPos(pos.x + way.dx, pos.y + offset - 1, pos.z + way.dz), wood, if (way.dz != 0) "z" else "x")
        }
        canopy(BlockPos(pos.x, pos.y + offset, pos.z), wood, leaves)
        dirtUnder(pos)
        return true
    }

    /** A shelf mushroom on the log at [pos], a quarter of the time, on a side with none beside it already. */
    private fun Tree.shelfMushroom(pos: BlockPos) {
        if (!chance(25.0)) return
        for (way in AROUND.shuffled(random)) {
            val at = pos.side(way)
            if (!replaceable(at) || Face.entries.any { nameAt(at.side(it)) == SHELF_MUSHROOM }) continue
            put(at, SHELF_MUSHROOM, CARDINAL to way.id)
            return
        }
    }

    private fun Tree.canopy(at: BlockPos, wood: String, leaves: String) {
        // Radii of five and six are as likely as each other, and each five times as likely as seven or eight.
        val r = listOf(5, 5, 5, 5, 5, 6, 6, 6, 6, 6, 7, 8).random(random) - 1
        val h = draw(5..6)
        val sameSign = random.nextBoolean()
        fun fits(pos: BlockPos) = nameAt(pos) == SHELF_MUSHROOM || nameAt(pos) == RED_SHRUB || leafFits(pos)
        fun layer(radius: Int, y: Int) {
            // The top two layers lose their whole edge, not just the corners.
            val topTwo = y == h - 1 || y == h - 2
            for (x in -radius..radius) for (z in -radius..radius) {
                if (topTwo && (abs(x) == radius || abs(z) == radius)) continue
                val bonus = if (fullerQuarter(x, z, sameSign)) 1 else if (topTwo) -1 else 0
                // Every cell's edge is tried, whether or not it reaches that far.
                val edge = if (chance(15.0)) 0 else 1
                if (abs(x) + abs(z) > radius + bonus - 1 + edge) continue
                leaf(BlockPos(at.x + x, at.y + y, at.z + z), leaves, ::fits)
            }
        }
        layer(r - 2, h - 1)
        layer(r - 1, h - 2)
        layer(r - 1, h - 3)
        for (y in h - 4 downTo 1) layer(r, y)
        // A cross of logs runs out along the axes through the widest layer.
        for (x in -r..r) for (z in -r..r) {
            if (!((x == 0 && r - abs(z) > 3) || (z == 0 && r - abs(x) > 3))) continue
            val bonus = if (fullerQuarter(x, z, sameSign)) 1 else 0
            if (abs(x) + abs(z) > r - 2 + bonus) continue
            log(BlockPos(at.x + x, at.y + h - 4, at.z + z), wood, if (z == 0) "x" else "z")
        }
        layer(r - 1, 0)
        layer(if (r < 3) 1 else if (r < 5) r - 2 else 2, -1)
    }
}

// What each sapling grows into, by the values Bedrock gives its trees.
private val BLOB_HOLES = listOf(50.0, 50.0, 50.0, 100.0)
private val OAK = StraightTrunk(4..6, BlobCanopy(-3..0, 1, 1, 0.5, BLOB_HOLES))
private val BIRCH = StraightTrunk(5..7, BlobCanopy(-3..0, 1, 1, 0.5, BLOB_HOLES))
private val JUNGLE = StraightTrunk(4..10, BlobCanopy(-3..0, 1, 1, 0.5, BLOB_HOLES, vinePercent = 25.0, vineSteps = 4), vinePercent = 100.0 / 3)
private val SPRUCE = StraightTrunk(6..9, SpruceCanopy(1..2, 0..2, 2..3), heightModifier = -2..0)
private val ACACIA = BranchingTrunk(
    5, listOf(3, 3), width = 1, canopyFrom = 3, leanHeight = 1..4, leanSteps = 1..3, leans = true,
    branchLength = 1..3, branchPosition = 1..2, branchPercent = 100.0, branchCanopy = AcaciaCanopy(2, simplified = true), canopy = AcaciaCanopy(3),
)
private val ROOFED = BranchingTrunk(
    6, listOf(3, 2), width = 2, canopyFrom = 3, leanHeight = 0..3, leanSteps = 0..2, leans = false,
    branchLength = 2..4, branchPosition = 1..1, branchPercent = 100.0 / 3, branchCanopy = AcaciaCanopy(2, simplified = true),
    canopy = RoofedCanopy(4, coreWidth = 2, outerRadius = 2, innerRadius = 3),
)
private val MEGA_SPRUCE = MegaTrunk(13, listOf(3, 15), MegaPineCanopy(13..17, 0, 3.5, 2), PODZOL, PodzolPatches(5, 2))
private val MEGA_PINE = MegaTrunk(13, listOf(3, 15), MegaPineCanopy(3..7, 0, 3.5, 2), PODZOL, PodzolPatches(5, 2))
private val MEGA_JUNGLE = MegaTrunk(
    10, listOf(3, 20), MegaCanopy(3..3, 2, 2), DIRT, branchLength = 5, branchInterval = 2..5, branchSlope = 0.5,
    branchCanopy = MegaCanopy(2..3, 1, 1, simplified = true), vinePercent = 100.0 / 3,
)
private val CHERRY = CherryTrunk(CherryCanopy(5, 4, 1, wideBottomHole = 25.0, cornerHole = 50.0, hanging = 16.6666, hangingFurther = 33.3333))
private val POPLAR = PoplarTrunk()

/** The leaves a poplar grows, each tree one colour of them. */
private val POPLAR_LEAVES = listOf("minecraft:orange_poplar_leaves", "minecraft:yellow_poplar_leaves", "minecraft:red_poplar_leaves")

/** The chance an oak sapling grows a fancy oak, and a square of spruce saplings a mega pine rather than a mega spruce. */
private const val FANCY_OAK_CHANCE = 0.1
private const val MEGA_PINE_CHANCE = 0.5

/**
 * The north-west corner of the square of four saplings like [sapling]
 * that the one at [pos] is part of, or null if it's in none.
 */
private fun FlatWorld.saplingSquare(pos: BlockPos, sapling: String): BlockPos? {
    fun isSapling(x: Int, z: Int) = stateAt(BlockPos(x, pos.y, z))?.name == sapling
    for ((dx, dz) in listOf(0 to 0, -1 to 0, 0 to -1, -1 to -1)) {
        val x = pos.x + dx
        val z = pos.z + dz
        if (isSapling(x, z) && isSapling(x + 1, z) && isSapling(x, z + 1) && isSapling(x + 1, z + 1)) return BlockPos(x, pos.y, z)
    }
    return null
}

/** Whether the sapling [sapling] at [pos] can grow at all: dark oaks and pale oaks grow only from four in a square. */
internal fun FlatWorld.canGrowTree(pos: BlockPos, sapling: BlockState): Boolean = when (sapling.name) {
    "minecraft:dark_oak_sapling", "minecraft:pale_oak_sapling" -> saplingSquare(pos, sapling.name) != null
    else -> true
}

/**
 * Grows the tree the sapling [sapling] at [pos] grows into, as the game
 * does, if it has room, or the tree an azalea or a planted mangrove
 * propagule grows. Four saplings of a spruce or jungle tree in a square
 * grow a mega tree, and dark oaks and pale oaks grow only that way. An oak
 * grows a fancy oak now and then. Returns where blocks changed, or null if
 * no tree grew.
 */
internal fun FlatWorld.growTree(pos: BlockPos, sapling: BlockState, random: Random = Random.Default): List<BlockPos>? {
    val tree = Tree(this, random)
    val name = sapling.name
    val kind = name.removePrefix("minecraft:").removeSuffix("_sapling")
    var at = pos
    var leaves = "minecraft:${kind}_leaves"
    val trunk: Trunk = when (name) {
        "minecraft:oak_sapling" -> if (random.nextDouble() < FANCY_OAK_CHANCE) return tree.fancyOak(pos)?.toList() else OAK
        "minecraft:birch_sapling" -> BIRCH
        "minecraft:acacia_sapling" -> ACACIA
        "minecraft:cherry_sapling" -> CHERRY
        "minecraft:poplar_sapling" -> POPLAR.also { leaves = POPLAR_LEAVES.random(random) }
        "minecraft:spruce_sapling" -> saplingSquare(pos, name)?.let { at = it; if (random.nextDouble() < MEGA_PINE_CHANCE) MEGA_PINE else MEGA_SPRUCE } ?: SPRUCE
        "minecraft:jungle_sapling" -> saplingSquare(pos, name)?.let { at = it; MEGA_JUNGLE } ?: JUNGLE
        "minecraft:dark_oak_sapling", "minecraft:pale_oak_sapling" -> saplingSquare(pos, name)?.let { at = it; ROOFED } ?: return null
        "minecraft:azalea", "minecraft:flowering_azalea" -> return tree.azalea(pos)?.toList()
        MANGROVE_PROPAGULE -> return tree.mangrove(pos)?.toList()
        else -> return null
    }
    val grew = with(trunk) { tree.place(at, height(random), "minecraft:${kind}_log", leaves) }
    return tree.changed.toList().takeIf { grew }
}

/**
 * A fancy oak, as an oak sapling grows now and then: a tall trunk with
 * branches reaching out from its upper part, each ending in a ball of
 * leaves, as tall as there's room for, if that's four or more. Returns
 * where blocks changed, or null if it had no room.
 */
private fun Tree.fancyOak(pos: BlockPos): Set<BlockPos>? {
    val wood = "minecraft:oak_log"
    val leaves = "minecraft:oak_leaves"
    if (!soil(pos.side(Face.DOWN))) return null
    val height = draw(3..14)
    fun free(at: BlockPos) = replaceable(at) || stateAt(at)?.holdsLeaves == true
    // As tall as the column above is free, cut short, but not below four.
    var room = height
    for (dy in 0..height + 1) if (!free(BlockPos(pos.x, pos.y + dy, pos.z))) {
        room = dy - 2
        break
    }
    if (room < height && room < 4) return null

    val tall = room + 2
    val trunkTop = floor(tall * 0.618).toInt()
    val branchLimit = pos.y + trunkTop
    val foliageStart = tall - 5

    /** Every block along the line from [from] to [to], placing logs along it, or seeing whether it's all free, for [place] false. */
    fun limb(from: BlockPos, to: BlockPos, place: Boolean): Boolean {
        if (!place && from == to) return true
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val steps = maxOf(abs(dx), abs(dy), abs(dz))
        for (i in 0..steps) {
            val at = BlockPos(
                from.x + floor(0.5 + i * dx.toDouble() / steps).toInt(), from.y + floor(0.5 + i * dy.toDouble() / steps).toInt(),
                from.z + floor(0.5 + i * dz.toDouble() / steps).toInt(),
            )
            if (place) {
                val sideways = maxOf(abs(at.x - from.x), abs(at.z - from.z))
                val axis = if (sideways == 0) "y" else if (abs(at.x - from.x) == sideways) "x" else "z"
                log(at, wood, axis)
            } else if (!free(at)) {
                return false
            }
        }
        return true
    }

    // Where each ball of leaves goes, with where its branch meets the trunk.
    val foliage = mutableListOf(BlockPos(pos.x, pos.y + foliageStart, pos.z) to branchLimit)
    for (current in foliageStart downTo 0) {
        val shape = fancyShape(tall, current)
        if (shape < 0) continue
        val length = shape * (random.nextFloat() + 0.328)
        val angle = random.nextFloat() * PI * 2
        val bx = pos.x + floor(length * sin(angle) + 0.5).toInt()
        val bz = pos.z + floor(length * cos(angle) + 0.5).toInt()
        val ball = BlockPos(bx, pos.y + current - 1, bz)
        if (!limb(ball, BlockPos(bx, pos.y + current + 4, bz), place = false)) continue
        val out = sqrt(((pos.x - bx) * (pos.x - bx) + (pos.z - bz) * (pos.z - bz)).toDouble()) * 0.381
        val baseY = ball.y - out
        val attached = if (baseY > branchLimit) branchLimit else baseY.toInt()
        if (limb(BlockPos(pos.x, attached, pos.z), ball, place = false)) foliage += ball to attached
    }

    dirtUnder(pos)
    limb(pos, BlockPos(pos.x, pos.y + trunkTop, pos.z), place = true)
    val kept = foliage.filter { (_, attached) -> attached - pos.y >= tall * 0.2 }
    for ((ball, attached) in kept) {
        val start = BlockPos(pos.x, attached, pos.z)
        if (start != ball) limb(start, ball, place = true)
    }
    for ((ball, _) in kept) {
        // A ball five rows high, two out at the top and bottom and three between, rounded.
        for (y in 4 downTo 0) {
            val r = 2 + if (y != 4 && y != 0) 1 else 0
            for (dx in -r..r) for (dz in -r..r) {
                if ((abs(dx) + 0.5) * (abs(dx) + 0.5) + (abs(dz) + 0.5) * (abs(dz) + 0.5) > r * r) continue
                leaf(BlockPos(ball.x + dx, ball.y + y, ball.z + dz), leaves, ::replaceable)
            }
        }
    }
    return changed
}

/** How far a fancy oak's branches reach at [y] up a tree [height] tall: none in its lower part, and furthest halfway up. */
private fun fancyShape(height: Int, y: Int): Double {
    if (y < height * 0.3) return -1.0
    val middle = height / 2.0
    val fromMiddle = middle - y
    if (fromMiddle == 0.0) return middle * 0.5
    if (abs(fromMiddle) >= middle) return 0.0
    return sqrt(middle * middle - fromMiddle * fromMiddle) * 0.5
}

/**
 * The tree an azalea grows into with bone meal: an oak trunk bending to
 * one side at its top, over rooted dirt, with azalea leaves, some of them
 * flowering, scattered round it. Returns where blocks changed, or null if
 * it had no room.
 */
private fun Tree.azalea(pos: BlockPos): Set<BlockPos>? {
    if (!soil(pos.side(Face.DOWN))) return null
    val height = draw(4..6)
    for (dy in 0..height + 1) {
        val r = if (dy < 1) 0 else 1
        for (dx in -r..r) for (dz in -r..r) {
            val at = BlockPos(pos.x + dx, pos.y + dy, pos.z + dz)
            // The azalea itself gives way to the tree.
            if (at != pos && ((!replaceable(at) && stateAt(at)?.holdsLeaves != true) || nameAt(at) == VINE)) return null
        }
    }
    put(pos, AIR)
    put(pos.side(Face.DOWN), ROOTED_DIRT)
    val bend = AROUND[random.nextInt(AROUND.size)]
    val wood = "minecraft:oak_log"
    var x = pos.x
    var z = pos.z
    val leafAt = mutableListOf<BlockPos>()
    for (i in 0 until height) {
        if (i + 1 >= height - 1 + random.nextInt(2)) {
            x += bend.dx
            z += bend.dz
        }
        log(BlockPos(x, pos.y + i, z), wood)
        if (i >= 3) leafAt += BlockPos(x, pos.y + i, z)
    }
    for (i in 0..draw(1..2)) {
        log(BlockPos(x, pos.y + height, z), wood)
        leafAt += BlockPos(x, pos.y + height, z)
        x += bend.dx
        z += bend.dz
    }
    for (at in leafAt) repeat(50) {
        val leaf = BlockPos(at.x + random.nextInt(3) - random.nextInt(3), at.y + random.nextInt(2) - random.nextInt(2), at.z + random.nextInt(3) - random.nextInt(3))
        leaf(leaf, if (random.nextInt(4) == 0) "minecraft:azalea_leaves_flowered" else "minecraft:azalea_leaves", ::replaceable)
    }
    return changed
}

private const val MANGROVE_LOG = "minecraft:mangrove_log"
private const val MANGROVE_ROOTS = "minecraft:mangrove_roots"
private const val MUDDY_MANGROVE_ROOTS = "minecraft:muddy_mangrove_roots"
private const val MOSS_CARPET = "minecraft:moss_carpet"

/** What a mangrove's roots, and its logs, grow through as well as what any tree does: mud, roots, moss, vines and the like. */
private val MANGROVE_GROWS_THROUGH = setOf(
    MUD, MUDDY_MANGROVE_ROOTS, MANGROVE_ROOTS, MOSS_CARPET, VINE, MANGROVE_PROPAGULE, SNOW_LAYER,
)
private val MANGROVE_LOG_GROWS_THROUGH = MANGROVE_GROWS_THROUGH - SNOW_LAYER + setOf(MANGROVE_LOG, "minecraft:mangrove_leaves")

/** How far a mangrove's roots reach from its trunk, and how much further they only go down. */
private const val ROOT_REACH = 8
private const val ROOT_LENGTH = 15

/**
 * The tree a planted mangrove propagule grows into: most of them tall,
 * on roots arching out and down from a trunk raised off the ground, with
 * branches reaching up and out, leaves scattered round them, vines hanging
 * from those now and then, and propagules too. Returns where blocks
 * changed, or null if it had no room.
 */
private fun Tree.mangrove(pos: BlockPos): Set<BlockPos>? {
    val tall = random.nextFloat() < 0.85f
    val height = if (tall) 4 + random.nextInt(2) + random.nextInt(10) else 2 + random.nextInt(2) + random.nextInt(5)
    val trunk = BlockPos(pos.x, pos.y + if (tall) draw(3..7) else draw(1..3), pos.z)
    fun rootFits(at: BlockPos) = replaceable(at) || nameAt(at) in MANGROVE_GROWS_THROUGH
    fun logFits(at: BlockPos) = replaceable(at) || stateAt(at)?.holdsLeaves == true || nameAt(at) in MANGROVE_LOG_GROWS_THROUGH
    for (dy in 0..height + 1) {
        val r = if (dy < (if (tall) 3 else 2)) 0 else 2
        for (dx in -r..r) for (dz in -r..r) if (!logFits(BlockPos(trunk.x + dx, trunk.y + dy, trunk.z + dz))) return null
    }
    // The roots are worked out before any go down, and the tree doesn't grow if they can't.
    for (y in pos.y until trunk.y) if (!rootFits(BlockPos(pos.x, y, pos.z))) return null
    val roots = mutableListOf(trunk.side(Face.DOWN))
    for (way in AROUND) {
        val start = trunk.side(way)
        val reaching = mutableListOf<BlockPos>()
        if (!spreadRoots(start, way, trunk, reaching, 0, ::rootFits)) return null
        roots += reaching
        roots += start
    }

    put(pos, AIR)
    for (root in roots) {
        if (!rootFits(root)) continue
        put(root, if (nameAt(root) == MUD || nameAt(root) == MUDDY_MANGROVE_ROOTS) MUDDY_MANGROVE_ROOTS else MANGROVE_ROOTS)
        if (random.nextFloat() < 0.5f && isAir(root.side(Face.UP))) put(root.side(Face.UP), MOSS_CARPET)
    }

    fun mangroveLog(at: BlockPos) = logFits(at).also { if (it) put(at, MANGROVE_LOG, PILLAR_AXIS to "y") }
    val foliage = mutableListOf<BlockPos>()
    for (i in 0 until height) {
        val y = trunk.y + i
        if (mangroveLog(BlockPos(trunk.x, y, trunk.z)) && i < height - 1 && random.nextFloat() < 0.5f) {
            // A branch, reaching out a block at a time as it goes up.
            val way = AROUND[random.nextInt(AROUND.size)]
            var steps = if (tall) draw(1..6) else draw(1..4)
            var reach = draw(0..1)
            var top = y + reach
            var x = trunk.x
            var z = trunk.z
            while (reach < height && steps > 0) {
                if (reach >= 1) {
                    x += way.dx
                    z += way.dz
                    top = y + reach
                    if (mangroveLog(BlockPos(x, y + reach, z))) top++
                    foliage += BlockPos(x, y + reach, z)
                }
                reach++
                steps--
            }
            if (top - y > 1) foliage += listOf(BlockPos(x, top, z), BlockPos(x, top - 2, z))
        }
        if (i == height - 1) foliage += BlockPos(trunk.x, y + 1, trunk.z)
    }

    val leaves = mutableListOf<BlockPos>()
    for (at in foliage) repeat(70) {
        val leaf = BlockPos(at.x + random.nextInt(3) - random.nextInt(3), at.y + random.nextInt(2) - random.nextInt(2), at.z + random.nextInt(3) - random.nextInt(3))
        if (leaf(leaf, "minecraft:mangrove_leaves", ::replaceable)) leaves += leaf
    }
    // Vines hang from the leaves now and then, down to four blocks below them.
    for (leaf in leaves) for (side in listOf(Face.WEST, Face.EAST, Face.NORTH, Face.SOUTH)) {
        if (random.nextFloat() < 0.125f && isAir(leaf.side(side))) hangVines(leaf, 100.0, 5, listOf(side))
    }
    // Propagules hang from the leaves now and then, with two blocks of air under each, and none beside another.
    val taken = HashSet<BlockPos>()
    for (leaf in leaves.shuffled(random)) {
        val at = leaf.side(Face.DOWN)
        if (at in taken || random.nextFloat() >= 0.14f || !isAir(at) || !isAir(at.side(Face.DOWN))) continue
        for (dx in -1..1) for (dz in -1..1) taken += BlockPos(at.x + dx, at.y, at.z + dz)
        put(at, MANGROVE_PROPAGULE, HANGING to bit(true), PROPAGULE_STAGE to random.nextInt(MAX_PROPAGULE_STAGE + 1))
    }
    return changed
}

/**
 * Works out the roots of a mangrove going out from [at] towards [way], from
 * its trunk at [trunk], into [roots]: down, or on out now and then, close to
 * the trunk, and only down further off. Returns false if they'd reach too
 * far, or spread too wide, for the tree to grow.
 */
private fun Tree.spreadRoots(at: BlockPos, way: Face, trunk: BlockPos, roots: MutableList<BlockPos>, length: Int, fits: (BlockPos) -> Boolean): Boolean {
    if (length == ROOT_LENGTH || roots.size > ROOT_LENGTH) return false
    val below = at.side(Face.DOWN)
    val distance = abs(at.x - trunk.x) + abs(at.y - trunk.y) + abs(at.z - trunk.z)
    val next = when {
        distance > ROOT_REACH -> listOf(below)
        distance > ROOT_REACH - 3 -> if (random.nextFloat() < 0.2f) listOf(below, at.side(way).side(Face.DOWN)) else listOf(below)
        random.nextFloat() < 0.2f -> listOf(below)
        else -> listOf(if (random.nextBoolean()) at.side(way) else below)
    }
    for (root in next) {
        if (!fits(root)) continue
        roots += root
        if (!spreadRoots(root, way, trunk, roots, length + 1, fits)) return false
    }
    return true
}
