package org.koremc.world

import org.koremc.registry.AIR
import org.koremc.registry.BlockState
import kotlin.random.Random

internal const val BROWN_MUSHROOM = "minecraft:brown_mushroom"
internal const val RED_MUSHROOM = "minecraft:red_mushroom"
internal const val CRIMSON_FUNGUS = "minecraft:crimson_fungus"
internal const val WARPED_FUNGUS = "minecraft:warped_fungus"
internal const val CRIMSON_NYLIUM = "minecraft:crimson_nylium"
internal const val WARPED_NYLIUM = "minecraft:warped_nylium"
internal const val NETHERRACK = "minecraft:netherrack"
private const val MUSHROOM_STEM = "minecraft:mushroom_stem"
private const val MUSHROOM_PARTS = "huge_mushroom_bits"
private const val SHROOMLIGHT = "minecraft:shroomlight"
private const val NETHER_WART_BLOCK = "minecraft:nether_wart_block"
private const val SOUL_SOIL = "minecraft:soul_soil"

/**
 * Which face of a huge mushroom's block shows cap, as `huge_mushroom_bits`
 * numbers it: the pores inside, a corner or edge of the cap, its middle,
 * and the stem's sides.
 */
private const val PORES = 0
private const val NORTH_WEST = 1
private const val NORTH_EAST = 3
private const val CAP_MIDDLE = 5
private const val SOUTH_WEST = 7
private const val SOUTH_EAST = 9
private const val STEM = 10

/** What mushrooms grow on in any light: mycelium, podzol and nylium. */
private val MUSHROOM_GROUND = setOf(MYCELIUM, "minecraft:podzol", CRIMSON_NYLIUM, WARPED_NYLIUM)

/** The light from which a mushroom away from its own ground can't stay, and doesn't spread. */
private const val MUSHROOM_LIGHT = 13

/** How far around itself a mushroom looks at how many are there already, across and up or down, and the most that stops it spreading. */
private const val MUSHROOM_REACH = 4
private const val MUSHROOMS_NEAR = 5

internal val BlockState.isMushroom get() = name == BROWN_MUSHROOM || name == RED_MUSHROOM

/** Whether a mushroom can stay at [pos]: on its own ground, or on a whole top in dim light. */
internal fun FlatWorld.mushroomSurvives(pos: BlockPos): Boolean {
    val ground = stateAt(pos.below) ?: return false
    if (ground.name in MUSHROOM_GROUND) return true
    return ground.hasFullFace(Face.UP) && lightAt(pos) < MUSHROOM_LIGHT
}

/**
 * A mushroom spreading, a twenty-fifth of the time, unless five like it
 * are near: a few steps at random, through air it could grow in, to a
 * new one where it ends up.
 */
internal fun FlatWorld.spreadMushroom(pos: BlockPos, state: BlockState, growing: Growing) {
    val random = growing.random
    if (random.nextInt(25) != 0) return
    var near = MUSHROOMS_NEAR
    for (dx in -MUSHROOM_REACH..MUSHROOM_REACH) for (dy in -1..1) for (dz in -MUSHROOM_REACH..MUSHROOM_REACH) {
        if (stateAt(BlockPos(pos.x + dx, pos.y + dy, pos.z + dz))?.name == state.name && --near <= 0) return
    }
    fun step(from: BlockPos) = BlockPos(from.x + random.nextInt(3) - 1, from.y + random.nextInt(2) - random.nextInt(2), from.z + random.nextInt(3) - 1)
    fun fits(at: BlockPos) = at.y in MIN_Y..MAX_Y && stateAt(at)?.name == AIR && mushroomSurvives(at)
    var from = pos
    var to = step(from)
    repeat(4) {
        if (fits(to)) from = to
        to = step(from)
    }
    if (!fits(to)) return
    set(to, state)
    growing.changed += to
}

/**
 * The huge mushroom the mushroom [mushroom] at [pos] grows into with bone
 * meal, as the game grows it: a stem four to six tall, now and then twice
 * that, under a flat brown cap seven across, or a red dome. It needs air or
 * leaves where it grows, and dirt, mycelium, podzol or nylium under it.
 * Returns where blocks changed, or null if it didn't grow.
 */
internal fun FlatWorld.growHugeMushroom(pos: BlockPos, mushroom: BlockState, random: Random = Random.Default): List<BlockPos>? {
    val tree = Tree(this, random)
    val red = mushroom.name == RED_MUSHROOM
    var height = random.nextInt(3) + 4
    if (random.nextInt(12) == 0) height *= 2
    if (pos.y + height + 1 > MAX_Y) return null
    for (y in pos.y..pos.y + height + 1) {
        val r = if (y <= pos.y + 3) 0 else 3
        for (x in pos.x - r..pos.x + r) for (z in pos.z - r..pos.z + r) {
            val at = BlockPos(x, y, z)
            val state = stateAt(at) ?: return null
            if (at != pos && state.name != AIR && !state.isLeaves) return null
        }
    }
    val ground = stateAt(pos.below)?.name
    if (ground !in DIRTS && ground !in MUSHROOM_GROUND) return null

    val cap = if (red) "minecraft:red_mushroom_block" else "minecraft:brown_mushroom_block"
    fun place(at: BlockPos, name: String, part: Int) {
        val there = stateAt(at) ?: return
        if (there.replaceable || there.isLeaves || at == pos) tree.put(at, name, MUSHROOM_PARTS to part)
    }
    // A red dome is four layers deep; a brown cap is one, at the top.
    val top = pos.y + height
    for (y in (if (red) top - 3 else top)..top) {
        val r = if (!red) 3 else if (y < top) 2 else 1
        for (x in pos.x - r..pos.x + r) for (z in pos.z - r..pos.z + r) {
            val west = x == pos.x - r
            val east = x == pos.x + r
            val north = z == pos.z - r
            val south = z == pos.z + r
            var part = CAP_MIDDLE + (if (west) -1 else if (east) 1 else 0) + (if (north) -3 else if (south) 3 else 0)
            if (!red || y < top) {
                // The corners are left off, the cap rounded where they'd be.
                if ((west || east) && (north || south)) continue
                val inner = r - 1
                when {
                    (x == pos.x - inner && north) || (west && z == pos.z - inner) -> part = NORTH_WEST
                    (x == pos.x + inner && north) || (east && z == pos.z - inner) -> part = NORTH_EAST
                    (x == pos.x - inner && south) || (west && z == pos.z + inner) -> part = SOUTH_WEST
                    (x == pos.x + inner && south) || (east && z == pos.z + inner) -> part = SOUTH_EAST
                }
            }
            // A red dome is hollow below its top.
            if (part == CAP_MIDDLE && y < top) part = PORES
            if (part == PORES) continue
            place(BlockPos(x, y, z), cap, part)
        }
    }
    for (y in pos.y until top) place(BlockPos(pos.x, y, pos.z), MUSHROOM_STEM, STEM)
    return tree.changed.toList()
}

/** The nylium each fungus grows huge on, with the stem and wart it grows. */
private class FungusKind(val nylium: String, val stem: String, val wart: String)

private val FUNGI = mapOf(
    CRIMSON_FUNGUS to FungusKind(CRIMSON_NYLIUM, "minecraft:crimson_stem", NETHER_WART_BLOCK),
    WARPED_FUNGUS to FungusKind(WARPED_NYLIUM, "minecraft:warped_stem", "minecraft:warped_wart_block"),
)

/** Whether the fungus [fungus] at [pos] is on the nylium of its own kind, which it grows huge on. */
internal fun FlatWorld.onOwnNylium(pos: BlockPos, fungus: BlockState) = stateAt(pos.below)?.name == FUNGI[fungus.name]?.nylium

/**
 * The huge fungus the fungus [fungus] at [pos] grows into with bone meal,
 * as the game grows it: a stem four to thirteen tall, now and then twice
 * that, under a hat of wart, with shroomlights in it and, from a crimson
 * one's, weeping vines hanging now and then. Returns where blocks changed,
 * or null if it didn't grow.
 */
internal fun FlatWorld.growHugeFungus(pos: BlockPos, fungus: BlockState, random: Random = Random.Default): List<BlockPos>? {
    val kind = FUNGI[fungus.name] ?: return null
    if (!onOwnNylium(pos, fungus)) return null
    val tree = Tree(this, random)
    var height = random.nextInt(4, 14)
    if (random.nextInt(12) == 0) height *= 2
    if (pos.y + height + 1 > MAX_Y) return null
    val crimson = kind.wart == NETHER_WART_BLOCK
    fun replaceable(at: BlockPos, wartToo: Boolean): Boolean {
        val state = tree.stateAt(at) ?: return false
        return state.replaceable || (wartToo && (state.isLeaves || state.name == kind.wart || state.name == SHROOMLIGHT))
    }
    tree.put(pos, AIR)
    for (y in 0 until height) {
        val at = BlockPos(pos.x, pos.y + y, pos.z)
        if (replaceable(at, wartToo = true)) tree.put(at, kind.stem, "pillar_axis" to "y")
    }

    fun vines(at: BlockPos) {
        val below = at.below
        if (tree.stateAt(below)?.name != AIR) return
        var length = random.nextInt(1, 6)
        if (random.nextInt(7) == 0) length *= 2
        var y = below
        for (i in 0 until length) {
            if (tree.stateAt(y)?.name != AIR) break
            tree.put(y, WEEPING_VINES, "weeping_vines_age" to random.nextInt(23, 26))
            y = y.below
        }
    }
    fun hat(at: BlockPos, decorChance: Float, hatChance: Float, vineChance: Float) {
        if (random.nextFloat() < decorChance) {
            tree.put(at, SHROOMLIGHT)
        } else if (random.nextFloat() < hatChance) {
            tree.put(at, kind.wart)
            if (random.nextFloat() < vineChance) vines(at)
        }
    }
    val hatHeight = minOf(random.nextInt(1 + height / 3) + 5, height)
    val start = height - hatHeight
    for (y in start..height) {
        var r = if (y < height - random.nextInt(3)) 2 else 1
        if (hatHeight > 8 && y < start + 4) r = 3
        for (dx in -r..r) for (dz in -r..r) {
            val xEdge = dx == -r || dx == r
            val zEdge = dz == -r || dz == r
            val inside = !xEdge && !zEdge && y != height
            val at = BlockPos(pos.x + dx, pos.y + y, pos.z + dz)
            if (!replaceable(at, wartToo = false)) continue
            when {
                // The bottom of the hat hangs down in places, under its edge.
                y < start + 3 -> if (!inside) {
                    if (tree.stateAt(at.below)?.name == kind.wart) {
                        tree.put(at, kind.wart)
                    } else if (random.nextFloat() < 0.15f) {
                        tree.put(at, kind.wart)
                        if (crimson && random.nextInt(11) == 0) vines(at)
                    }
                }
                inside -> hat(at, 0.1f, 0.2f, if (crimson) 0.1f else 0f)
                xEdge && zEdge -> hat(at, 0.01f, 0.7f, if (crimson) 0.083f else 0f)
                else -> hat(at, 0.0005f, 0.98f, if (crimson) 0.07f else 0f)
            }
        }
    }
    return tree.changed.toList()
}

/** What grows on crimson nylium with bone meal, and on warped, each by how often, out of a hundred. */
private val CRIMSON_GROWTH = listOf("minecraft:crimson_roots" to 87, CRIMSON_FUNGUS to 11, WARPED_FUNGUS to 1)
private val WARPED_GROWTH = listOf("minecraft:warped_roots" to 85, "minecraft:crimson_roots" to 1, WARPED_FUNGUS to 13, CRIMSON_FUNGUS to 1)

/** What nether roots, fungi and sprouts grow on: nylium, soul soil, and dirt of any kind. */
private val NETHER_PLANT_GROUND = setOf(CRIMSON_NYLIUM, WARPED_NYLIUM, SOUL_SOIL) + DIRTS

/**
 * Bone meal on the nylium at [pos], [nylium], with air above it: roots and
 * fungi sprout on the nylium and the like around it, mostly of its own
 * kind, and on warped nylium, nether sprouts, and now and then twisting
 * vines. Notes where they grew in [changed].
 */
internal fun FlatWorld.sproutNylium(pos: BlockPos, nylium: BlockState, random: Random, changed: MutableCollection<BlockPos>) {
    val above = pos.above
    fun sprout(pick: () -> String) = repeat(3 * 3) {
        val at = BlockPos(above.x + random.nextInt(3) - random.nextInt(3), above.y, above.z + random.nextInt(3) - random.nextInt(3))
        if (stateAt(at)?.name != AIR || stateAt(at.below)?.name !in NETHER_PLANT_GROUND) return@repeat
        set(at, blocks.defaultOf(pick()) ?: return@repeat)
        changed += at
    }
    fun weighted(choices: List<Pair<String, Int>>): String {
        var roll = random.nextInt(choices.sumOf { it.second })
        return choices.first { (_, weight) -> (roll - weight).also { roll = it } < 0 }.first
    }
    if (nylium.name == CRIMSON_NYLIUM) return sprout { weighted(CRIMSON_GROWTH) }
    sprout { weighted(WARPED_GROWTH) }
    sprout { "minecraft:nether_sprouts" }
    if (random.nextInt(8) != 0) return
    // Twisting vines a block or two tall, now and then twice that, or just one.
    repeat(3 * 3) {
        val at = BlockPos(above.x + random.nextInt(-3, 4), above.y + random.nextInt(-1, 2), above.z + random.nextInt(-3, 4))
        if (stateAt(at)?.name != AIR || stateAt(at.below)?.name !in setOf(NETHERRACK, WARPED_NYLIUM, "minecraft:warped_wart_block")) return@repeat
        var length = random.nextInt(1, 3)
        if (random.nextInt(6) == 0) length *= 2
        if (random.nextInt(5) == 0) length = 1
        val vine = blocks.defaultOf(TWISTING_VINES) ?: return@repeat
        set(at, vine)
        changed += at
        growNetherVines(at, vine, length - 1, changed)
    }
}

/** The nylium of the kinds beside the netherrack at [pos], any way including diagonally. */
internal fun FlatWorld.nyliumBeside(pos: BlockPos): Set<String> {
    val found = HashSet<String>()
    for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
        val name = stateAt(BlockPos(pos.x + dx, pos.y + dy, pos.z + dz))?.name
        if (name == CRIMSON_NYLIUM || name == WARPED_NYLIUM) found += name
    }
    return found
}
