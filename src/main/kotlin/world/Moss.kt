package org.koremc.world

import org.koremc.registry.AIR
import kotlin.random.Random

internal const val MOSS_BLOCK = "minecraft:moss_block"
internal const val PALE_MOSS_BLOCK = "minecraft:pale_moss_block"

/** What moss spreads over: the overworld's stone, cave vines, and dirt of every kind. */
private val MOSS_SPREADS_OVER = setOf(
    "stone", "granite", "diorite", "andesite", "tuff", "deepslate", "cave_vines", "cave_vines_body_with_berries", "cave_vines_head_with_berries",
    "dirt", "grass_block", "podzol", "coarse_dirt", "mycelium", "dirt_with_roots", "moss_block", "mud", "muddy_mangrove_roots", "pale_moss_block",
).mapTo(HashSet()) { "minecraft:$it" }

/** A kind of moss: the block it spreads over the ground, and the plants that grow on it, by how often each does. */
private class Moss(val block: String, val plants: List<Pair<String, Int>>)

private val GREEN_MOSS = Moss(
    MOSS_BLOCK,
    listOf(
        "minecraft:flowering_azalea" to 4, "minecraft:azalea" to 7, "minecraft:moss_carpet" to 25, "minecraft:short_grass" to 50,
        "minecraft:tall_grass" to 10,
    ),
)
private val PALE_MOSS = Moss(PALE_MOSS_BLOCK, listOf("minecraft:pale_moss_carpet" to 25, "minecraft:short_grass" to 25, "minecraft:tall_grass" to 10))

// How a patch of moss spreads: how far each way, as well as a block, how far up or down it looks for the ground, how often
// the columns along its edge are covered, and how often a plant grows on the moss.
private val PATCH_REACH = 1..2
private const val PATCH_DEPTH = 5
private const val EDGE_CHANCE = 0.75f
private const val PLANT_CHANCE = 0.6f

/**
 * Bone meal on the moss, or pale moss, block at [pos]: a patch of it
 * spreads over the ground around the block, as the game spreads it, two
 * or three blocks each way, but for its corners, and most of its edge.
 * In each column, it finds the ground within five blocks up or down,
 * under air, with a whole top, and covers it, if it's stone, dirt or cave
 * vines, or the moss already. Plants grow on most of the moss: azaleas,
 * grass and moss carpets, or for pale moss, grass and pale moss carpets.
 * Returns where blocks changed, none if no ground was found.
 */
internal fun FlatWorld.spreadMoss(pos: BlockPos, random: Random): List<BlockPos> {
    val moss = if (stateAt(pos)?.name == PALE_MOSS_BLOCK) PALE_MOSS else GREEN_MOSS
    val block = blocks.defaultOf(moss.block) ?: return emptyList()
    val origin = pos.side(Face.UP)
    val reachX = random.nextInt(PATCH_REACH.first, PATCH_REACH.last + 1) + 1
    val reachZ = random.nextInt(PATCH_REACH.first, PATCH_REACH.last + 1) + 1
    val changed = mutableListOf<BlockPos>()
    val covered = mutableListOf<BlockPos>()
    fun isAir(at: BlockPos) = at.y in MIN_Y..MAX_Y && stateAt(at)?.name == AIR
    for (dx in -reachX..reachX) for (dz in -reachZ..reachZ) {
        val edgeX = dx == -reachX || dx == reachX
        val edgeZ = dz == -reachZ || dz == reachZ
        if ((edgeX && edgeZ) || ((edgeX || edgeZ) && random.nextFloat() > EDGE_CHANCE)) continue
        // Down through the air, then up out of the ground.
        var at = BlockPos(origin.x + dx, origin.y, origin.z + dz)
        repeat(PATCH_DEPTH) { if (isAir(at)) at = at.side(Face.DOWN) }
        repeat(PATCH_DEPTH) { if (at.y in MIN_Y..MAX_Y && !isAir(at)) at = at.side(Face.UP) }
        val ground = at.side(Face.DOWN)
        val there = stateAt(ground)
        if (!isAir(at) || there?.hasFullFace(Face.UP) != true) continue
        if (there.name != moss.block) {
            if (there.name !in MOSS_SPREADS_OVER) continue
            set(ground, block)
            changed += ground
        }
        covered += ground
    }
    for (ground in covered) if (random.nextFloat() < PLANT_CHANCE) changed += plantOnMoss(ground.side(Face.UP), moss, random)
    return changed
}

/** Plants one of [moss]'s plants at [pos], picked by how often each grows, if it can stand there, and a tall one has room. Returns where blocks changed. */
private fun FlatWorld.plantOnMoss(pos: BlockPos, moss: Moss, random: Random): List<BlockPos> {
    var pick = random.nextInt(moss.plants.sumOf { it.second })
    val name = moss.plants.first { (_, weight) -> (pick < weight).also { pick -= weight } }.first
    val plant = blocks.defaultOf(name) ?: return emptyList()
    if (!survives(pos, plant)) return emptyList()
    if (UPPER_BLOCK !in plant.properties) {
        set(pos, plant)
        return listOf(pos)
    }
    val top = pos.side(Face.UP)
    if (top.y > MAX_Y || stateAt(top)?.name != AIR) return emptyList()
    set(pos, plant)
    set(top, blocks.find(name, plant.properties + (UPPER_BLOCK to bit(true))) ?: return listOf(pos))
    return listOf(pos, top)
}
