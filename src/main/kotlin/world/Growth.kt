package org.koremc.world

import org.koremc.registry.AIR
import org.koremc.registry.BlockState
import org.koremc.registry.ItemStacks
import kotlin.math.abs
import kotlin.random.Random

/** How many chunks around each player the world ticks: what grows, and what the sky does. It's Bedrock's simulation distance, as worlds start with it. */
const val SIMULATION_DISTANCE = 4

internal const val GROWTH = "growth"
internal const val AGE = "age"
private const val MOISTURE = "moisturized_amount"
internal const val PERSISTENT = "persistent_bit"
internal const val LEAF_UPDATE = "update_bit"
private const val BLOCK_FACE = "minecraft:block_face"
internal const val KELP_AGE = "kelp_age"
internal const val VINE_AGE = "growing_plant_age"
internal const val PROPAGULE_STAGE = "propagule_stage"
private const val BAMBOO_LEAVES = "bamboo_leaf_size"
private const val BAMBOO_THICKNESS = "bamboo_stalk_thickness"
internal const val BAMBOO_DONE = "age_bit"

/** A sapling's age: whether it's past its first stage, and grows into its tree next. */
internal const val SAPLING_AGE = "age_bit"

internal const val GRASS_BLOCK = "minecraft:grass_block"
internal const val MYCELIUM = "minecraft:mycelium"
internal const val REEDS = "minecraft:reeds"
internal const val CACTUS = "minecraft:cactus"
private const val CACTUS_FLOWER = "minecraft:cactus_flower"
internal const val MELON = "minecraft:melon_block"
internal const val MELON_STEM = "minecraft:melon_stem"
internal const val PUMPKIN_STEM = "minecraft:pumpkin_stem"
internal const val TORCHFLOWER_CROP = "minecraft:torchflower_crop"
private const val TORCHFLOWER = "minecraft:torchflower"
internal const val PITCHER_CROP = "minecraft:pitcher_crop"
internal const val SWEET_BERRY_BUSH = "minecraft:sweet_berry_bush"
internal const val COCOA = "minecraft:cocoa"
internal const val NETHER_WART = "minecraft:nether_wart"
private const val SOUL_SAND = "minecraft:soul_sand"
internal const val KELP = "minecraft:kelp"
internal const val BAMBOO = "minecraft:bamboo"
internal const val BAMBOO_SAPLING = "minecraft:bamboo_sapling"
internal const val CAVE_VINES = "minecraft:cave_vines"
internal const val CAVE_VINES_BERRIES_HEAD = "minecraft:cave_vines_head_with_berries"
internal const val CAVE_VINES_BERRIES_BODY = "minecraft:cave_vines_body_with_berries"
internal const val TWISTING_VINES = "minecraft:twisting_vines"
internal const val WEEPING_VINES = "minecraft:weeping_vines"
internal const val MANGROVE_PROPAGULE = "minecraft:mangrove_propagule"
private const val ICE = "minecraft:ice"
internal const val WATERLILY = "minecraft:waterlily"
internal const val FROG_SPAWN = "minecraft:frog_spawn"
private const val FROSTED_ICE = "minecraft:frosted_ice"
private const val BUDDING_AMETHYST = "minecraft:budding_amethyst"
private const val DRIPSTONE_BLOCK = "minecraft:dripstone_block"
internal const val MUD = "minecraft:mud"
internal const val CLAY = "minecraft:clay"

/** The crops that grow through eight stages on farmland, from seed to harvest. */
internal val CROPS = setOf("minecraft:wheat", "minecraft:carrots", "minecraft:potatoes", "minecraft:beetroot")
internal const val MAX_CROP_GROWTH = 7

/** The stems melons and pumpkins grow from, with the fruit each grows. */
internal val STEM_FRUIT = mapOf(MELON_STEM to MELON, PUMPKIN_STEM to PUMPKIN)

/** What grows only on farmland, keeping it from drying back to dirt. */
private val ON_FARMLAND = CROPS + STEM_FRUIT.keys + setOf(TORCHFLOWER_CROP, PITCHER_CROP)

/** How tall a torchflower crop and a pitcher crop grow, and the stage a pitcher crop grows its top half at. */
private const val MAX_TORCHFLOWER_GROWTH = 1
internal const val MAX_PITCHER_GROWTH = 4
internal const val PITCHER_TOP_GROWTH = 3

/** The blocks plants like bushes, berries and melons grow on: dirt of every kind, and moss and mud. */
internal val DIRTS = setOf(
    GRASS_BLOCK, DIRT, COARSE_DIRT, ROOTED_DIRT, FARMLAND, "minecraft:podzol", MYCELIUM, "minecraft:moss_block", "minecraft:pale_moss_block",
    MUD, "minecraft:muddy_mangrove_roots",
)

/** What sugar cane grows on, with water beside it, and what cactus grows on. */
private val CANE_SOIL = DIRTS - FARMLAND + setOf("minecraft:sand", "minecraft:red_sand", "minecraft:suspicious_sand")
private val CACTUS_SOIL = setOf("minecraft:sand", "minecraft:red_sand", "minecraft:suspicious_sand")

/** How tall sugar cane and cactus grow by themselves, and bamboo, kelp and vines at the most. */
internal const val CANE_HEIGHT = 3
internal const val BAMBOO_HEIGHT = 16
internal const val MAX_VINE_AGE = 25
internal const val MAX_PROPAGULE_STAGE = 4

/** The light, at the least, that crops, stems, bamboo and berries grow in, and grass spreads in. */
private const val GROWING_LIGHT = 9

/** The blocks' light at which ice and snow melt, from it up. */
private const val MELTING_LIGHT = 12

/** How far from wood, in steps through leaves, leaves stay: far enough for the furthest leaves of the trees the game grows. */
private const val LEAF_REACH = 7

/** How far around itself a block of copper looks at other copper as it weathers. */
private const val WEATHERING_REACH = 4

// The chances of what happens on a random tick to the blocks that do it by chance.
private const val WEATHERING_CHANCE = 64f / 1125
private const val KELP_CHANCE = 0.14
private const val VINE_CHANCE = 0.1
private const val BERRIES_CHANCE = 0.11
private const val DRIP_WATER_CHANCE = 45f / 256
private const val DRIP_LAVA_CHANCE = 15f / 256
private const val DRIPSTONE_GROWTH_CHANCE = 64f / 5625

/** How far below its tip a stalactite drips into a cauldron, and the longest one that grows. */
private const val DRIP_REACH = 11
private const val DRIPSTONE_LENGTH = 7

/** Each stage of an amethyst bud as it grows on budding amethyst, to a cluster. */
private val BUD_STAGES = listOf("minecraft:small_amethyst_bud", "minecraft:medium_amethyst_bud", "minecraft:large_amethyst_bud", "minecraft:amethyst_cluster")

/** Wood, which keeps the leaves near it on their tree: logs and wood of every kind, stripped or not, and the nether's stems. */
internal val BlockState.holdsLeaves: Boolean
    get() = name.endsWith("_log") || name.endsWith("_wood") || name.endsWith("_hyphae") ||
        name.removePrefix("minecraft:stripped_").removePrefix("minecraft:") in setOf("crimson_stem", "warped_stem")

/** Leaves of a tree, which fall once their wood goes, rather than those placed by a player, which stay. */
private val BlockState.fallsWithoutWood get() = LEAF_UPDATE in properties && properties[PERSISTENT] == 0.toByte()

/** What a tick's random ticks changed as they went: where blocks changed, what broke, and what else happened there. */
internal class Growing(val random: Random) {
    val changed = LinkedHashSet<BlockPos>()
    val broken = linkedMapOf<BlockPos, BrokenBlock>()
    val events = mutableListOf<WorldEvent>()
}

/** A block that does something when a random tick lands on it, and what it does. */
private class RandomTicker(val ticks: FlatWorld.(BlockState) -> Boolean, val tick: FlatWorld.(BlockPos, BlockState, Growing) -> Unit)

private val RANDOM_TICKERS = listOf(
    RandomTicker({ it.name in CROPS }, FlatWorld::growCrop),
    RandomTicker({ it.name == TORCHFLOWER_CROP }, FlatWorld::growTorchflower),
    RandomTicker({ it.name == PITCHER_CROP }, FlatWorld::growPitcher),
    RandomTicker({ it.name in STEM_FRUIT }, FlatWorld::growStem),
    RandomTicker({ it.name == REEDS }, FlatWorld::growCane),
    RandomTicker({ it.name == CACTUS }, FlatWorld::growCactus),
    RandomTicker({ it.name == BAMBOO || it.name == BAMBOO_SAPLING }, FlatWorld::growBambooStalk),
    RandomTicker({ it.name == KELP }, FlatWorld::growKelp),
    RandomTicker({ it.name == SWEET_BERRY_BUSH }, FlatWorld::growBerries),
    RandomTicker({ it.name == COCOA }, FlatWorld::growCocoa),
    RandomTicker({ it.name == NETHER_WART }, FlatWorld::growWart),
    RandomTicker({ it.isCaveVines }, FlatWorld::growCaveVines),
    RandomTicker({ it.name == TWISTING_VINES || it.name == WEEPING_VINES }, FlatWorld::growNetherVines),
    RandomTicker({ it.name == MANGROVE_PROPAGULE }, FlatWorld::growPropagule),
    RandomTicker({ it.isSapling }, FlatWorld::growSapling),
    RandomTicker({ it.isMushroom }, FlatWorld::spreadMushroom),
    RandomTicker({ it.name == GRASS_BLOCK || it.name == MYCELIUM }, FlatWorld::spreadGrass),
    RandomTicker({ it.name == FARMLAND }, FlatWorld::moisten),
    RandomTicker({ it.fallsWithoutWood }, FlatWorld::decay),
    RandomTicker({ it.name == ICE }, FlatWorld::meltIce),
    RandomTicker({ it.liquid == Liquid.LAVA }, FlatWorld::lavaSetsFire),
    RandomTicker({ it.name == SNOW_LAYER }, FlatWorld::meltSnow),
    RandomTicker({ weathers(it) }, FlatWorld::weather),
    RandomTicker({ it.name == BUDDING_AMETHYST }, FlatWorld::growBud),
    RandomTicker({ it.name == POINTED_DRIPSTONE && it.properties[HANGING] == 1.toByte() }, FlatWorld::drip),
)

/** The network IDs of every block a random tick does something to, found the first time they're asked for. */
internal fun FlatWorld.randomTickers(): Set<Int> = randomTicking ?: blocks.states
    .filter { state -> RANDOM_TICKERS.any { it.ticks(this, state) } }
    .mapTo(HashSet()) { it.networkId }
    .also { randomTicking = it }

/**
 * Gives random ticks to the blocks in the chunks within [SIMULATION_DISTANCE]
 * of [players], by their chunks, as the game does: in each sub-chunk with
 * any block that does something on one, as many blocks picked at random
 * as the `randomTickSpeed` rule says. What grows grows a stage, or spreads,
 * now and then, and what dries, decays, melts or weathers does so. Run
 * once a tick. Returns what broke, like leaves with no wood left, and
 * where blocks changed, with the world settled around them. With growth
 * switched off, none land.
 */
fun FlatWorld.tickRandomly(players: Collection<ChunkPos>, random: Random = Random.Default): Changes {
    val speed = if (features.growth) rules.randomTickSpeed else 0
    if (speed <= 0 || players.isEmpty()) return Changes(emptyMap(), emptyList())
    val tickers = randomTickers()
    val growing = Growing(random)
    for (chunk in players.flatMap { chunksAround(it, SIMULATION_DISTANCE) }.distinct()) {
        for ((index, storage) in chunkAt(chunk).subChunks.withIndex()) {
            if (storage.palette.none { it in tickers }) continue
            repeat(speed) {
                val pos = BlockPos(chunk.x * 16 + random.nextInt(16), MIN_Y + index * 16 + random.nextInt(16), chunk.z * 16 + random.nextInt(16))
                stateAt(pos)?.takeIf { it.networkId in tickers }?.let { randomTick(pos, it, growing) }
            }
        }
    }
    val reshaped = LinkedHashSet(growing.changed)
    reshaped += settle(growing.changed + growing.broken.keys).reshaped
    reshaped.removeAll(growing.broken.keys)
    return Changes(growing.broken, reshaped.toList(), events = growing.events)
}

/** What a random tick landing on [state], at [pos], does: the first of [RANDOM_TICKERS] it's for, if any. */
internal fun FlatWorld.randomTick(pos: BlockPos, state: BlockState, growing: Growing) {
    RANDOM_TICKERS.firstOrNull { it.ticks(this, state) }?.tick?.invoke(this, pos, state, growing)
}

/** Puts [state] at [pos] with [changes] to its properties, if the game has that state, noting the change in [growing]. */
private fun FlatWorld.grow(pos: BlockPos, state: BlockState, growing: Growing, vararg changes: Pair<String, Any>) {
    blocks.find(state.name, state.properties + changes)?.let { set(pos, it) } ?: return
    growing.changed += pos
}

/** Puts the block [name] at [pos], in its default state but for [properties], if the game has it, noting the change in [growing]. */
private fun FlatWorld.put(pos: BlockPos, name: String, growing: Growing, vararg properties: Pair<String, Any>) {
    val default = blocks.defaultOf(name) ?: return
    set(pos, blocks.find(name, default.properties + properties) ?: return)
    growing.changed += pos
}

/** The whole number [property] of this block holds, or 0 if it has none. */
internal fun BlockState.number(property: String) = properties[property] as? Int ?: 0

internal val BlockPos.above get() = side(Face.UP)
internal val BlockPos.below get() = side(Face.DOWN)

/**
 * How fast the crop [state] at [pos] grows: faster on farmland, more so
 * wet, with farmland around it, and half as fast beside the same crop in
 * rows both ways, or diagonally, as the game reckons it.
 */
private fun FlatWorld.growthSpeed(pos: BlockPos, state: BlockState): Float {
    var speed = 1f
    for (dx in -1..1) for (dz in -1..1) {
        val soil = stateAt(BlockPos(pos.x + dx, pos.y - 1, pos.z + dz))?.takeIf { it.name == FARMLAND } ?: continue
        val points = if (soil.number(MOISTURE) > 0) 3f else 1f
        speed += if (dx == 0 && dz == 0) points else points / 4
    }
    fun same(dx: Int, dz: Int) = stateAt(BlockPos(pos.x + dx, pos.y, pos.z + dz))?.name == state.name
    val rows = (same(-1, 0) || same(1, 0)) && (same(0, -1) || same(0, 1))
    if (rows || same(-1, -1) || same(1, -1) || same(-1, 1) || same(1, 1)) speed /= 2
    return speed
}

/** Whether the crop [state] at [pos] grows on this tick: in light enough, by a chance its [growthSpeed] sets. */
private fun FlatWorld.cropGrows(pos: BlockPos, state: BlockState, random: Random) =
    random.nextInt((25 / growthSpeed(pos, state)).toInt() + 1) == 0 && internalLightAt(pos) >= GROWING_LIGHT

/** A crop growing a stage, now and then, in the light. */
private fun FlatWorld.growCrop(pos: BlockPos, state: BlockState, growing: Growing) {
    val growth = state.number(GROWTH)
    if (growth < MAX_CROP_GROWTH && cropGrows(pos, state, growing.random)) grow(pos, state, growing, GROWTH to growth + 1)
}

/** A torchflower crop growing a stage, now and then, two thirds as often as other crops, and flowering after the second. */
private fun FlatWorld.growTorchflower(pos: BlockPos, state: BlockState, growing: Growing) {
    if (growing.random.nextInt(3) == 0 || !cropGrows(pos, state, growing.random)) return
    growTorchflower(pos, state, growing.changed)
}

/** The torchflower crop [state] at [pos] a stage on, or flowering after its last. Notes where it changed in [changed]. */
internal fun FlatWorld.growTorchflower(pos: BlockPos, state: BlockState, changed: MutableCollection<BlockPos>) {
    val growth = state.number(GROWTH)
    val next = if (growth < MAX_TORCHFLOWER_GROWTH) blocks.find(state.name, state.properties + (GROWTH to growth + 1)) else blocks.defaultOf(TORCHFLOWER)
    set(pos, next ?: return)
    changed += pos
}

/** A pitcher crop growing a stage, now and then, by its lower half. */
private fun FlatWorld.growPitcher(pos: BlockPos, state: BlockState, growing: Growing) {
    if (state.properties[UPPER_BLOCK] == 1.toByte() || !cropGrows(pos, state, growing.random)) return
    growPitcher(pos, state, growing.changed)
}

/**
 * The pitcher crop whose lower half is at [pos], [state], a stage on, if
 * it can: from its fourth stage, it takes the space above it too, which
 * must be free. Notes where it changed in [changed]. Returns whether it grew.
 */
internal fun FlatWorld.growPitcher(pos: BlockPos, state: BlockState, changed: MutableCollection<BlockPos>): Boolean {
    val growth = state.number(GROWTH) + 1
    if (growth > MAX_PITCHER_GROWTH) return false
    val above = stateAt(pos.above)
    if (growth >= PITCHER_TOP_GROWTH && above?.name != AIR && !(above?.name == PITCHER_CROP && above.properties[UPPER_BLOCK] == 1.toByte())) return false
    set(pos, blocks.find(PITCHER_CROP, state.properties + (GROWTH to growth)) ?: return false)
    changed += pos
    if (growth >= PITCHER_TOP_GROWTH) {
        blocks.find(PITCHER_CROP, state.properties + mapOf(GROWTH to growth, UPPER_BLOCK to bit(true)))?.let { set(pos.above, it); changed += pos.above }
    }
    return true
}

/**
 * A melon or pumpkin stem growing a stage, now and then, in the light, and
 * once grown, its fruit on one of its sides picked at random, if that's
 * free with dirt, grass or farmland under it, and it has none already.
 */
private fun FlatWorld.growStem(pos: BlockPos, state: BlockState, growing: Growing) {
    if (!cropGrows(pos, state, growing.random)) return
    val growth = state.number(GROWTH)
    if (growth < MAX_CROP_GROWTH) return growStem(pos, state, growth + 1, growing.changed)
    val fruit = STEM_FRUIT.getValue(state.name)
    if (AROUND.any { stateAt(pos.side(it))?.name == fruit }) return
    val at = pos.side(AROUND.random(growing.random))
    if (stateAt(at)?.name != AIR || stateAt(at.below)?.name !in DIRTS) return
    put(at, fruit, growing)
}

/** The stem [state] at [pos] grown to [growth], bending towards fruit beside it once grown. Notes where it changed in [changed]. */
internal fun FlatWorld.growStem(pos: BlockPos, state: BlockState, growth: Int, changed: MutableCollection<BlockPos>) {
    val grown = blocks.find(state.name, state.properties + (GROWTH to growth)) ?: return
    set(pos, blocks.find(state.name, grown.properties + (FACING_DIRECTION to stemFacing(pos, grown))) ?: grown)
    changed += pos
}

/** Which way the stem [stem] at [pos] points: down, or once grown, towards the fruit beside it, if any, east first, then west, north and south. */
internal fun FlatWorld.stemFacing(pos: BlockPos, stem: BlockState): Int {
    if (stem.number(GROWTH) < MAX_CROP_GROWTH) return Face.DOWN.ordinal
    val fruit = STEM_FRUIT[stem.name] ?: return Face.DOWN.ordinal
    return listOf(Face.EAST, Face.WEST, Face.NORTH, Face.SOUTH).firstOrNull { stateAt(pos.side(it))?.name == fruit }?.ordinal ?: Face.DOWN.ordinal
}

/** Breaks [state] at [pos] if it can't stay there any more, as cane with its water gone, noting it in [growing]. Returns whether it broke. */
private fun FlatWorld.uproots(pos: BlockPos, state: BlockState, growing: Growing): Boolean {
    if (survives(pos, state)) return false
    breakAt(pos, state, growing.broken)
    growing.changed += pos
    return true
}

/** How many blocks of [name] stand in the column from [pos] down, [pos] included. */
internal fun FlatWorld.heightBelow(pos: BlockPos, name: String, most: Int): Int {
    var height = 0
    while (height < most && stateAt(BlockPos(pos.x, pos.y - height, pos.z))?.name == name) height++
    return height
}

/**
 * Sugar cane growing: with space above, the top of a column under three
 * tall counts its age up, and grows a block once that comes round, the new
 * block starting its own count. Cane with no water left by it breaks.
 */
private fun FlatWorld.growCane(pos: BlockPos, state: BlockState, growing: Growing) {
    if (uproots(pos, state, growing)) return
    if (stateAt(pos.above)?.name != AIR || heightBelow(pos, REEDS, CANE_HEIGHT) >= CANE_HEIGHT) return
    val age = state.number(AGE)
    if (age < 15) return grow(pos, state, growing, AGE to age + 1)
    put(pos.above, REEDS, growing)
    grow(pos, state, growing, AGE to 0)
}

/**
 * Cactus growing as sugar cane does, but that at its ninth tick, it may
 * flower instead, if a cactus could stand above it: a tenth of the time,
 * or a quarter, at three tall. A cactus with a block beside it breaks.
 */
private fun FlatWorld.growCactus(pos: BlockPos, state: BlockState, growing: Growing) {
    if (uproots(pos, state, growing)) return
    if (stateAt(pos.above)?.name != AIR) return
    val height = heightBelow(pos, CACTUS, CANE_HEIGHT)
    val age = state.number(AGE)
    if (height >= CANE_HEIGHT && age == 15) return
    if (age == 8 && survives(pos.above, state)) {
        if (growing.random.nextDouble() <= if (height >= CANE_HEIGHT) 0.25 else 0.1) put(pos.above, CACTUS_FLOWER, growing)
    } else if (age == 15 && height < CANE_HEIGHT) {
        put(pos.above, CACTUS, growing)
        return grow(pos, state, growing, AGE to 0)
    }
    if (age < 15) grow(pos, state, growing, AGE to age + 1)
}

/**
 * Bamboo growing a third of the time, from the top of a stalk still
 * growing, or a shoot, with space above and light there, up to sixteen
 * tall.
 */
private fun FlatWorld.growBambooStalk(pos: BlockPos, state: BlockState, growing: Growing) {
    if (growing.random.nextInt(3) != 0 || stateAt(pos.above)?.name != AIR || lightAt(pos.above) < GROWING_LIGHT) return
    if (state.name == BAMBOO_SAPLING) return growBambooShoot(pos, growing.changed)
    if (state.properties[BAMBOO_DONE] == 1.toByte()) return
    val height = heightBelow(pos, BAMBOO, BAMBOO_HEIGHT)
    if (height < BAMBOO_HEIGHT) growBamboo(pos, state, height, growing.random, growing.changed)
}

/** A bamboo shoot at [pos] growing into bamboo, with a stalk above it. Notes where blocks changed in [changed]. */
internal fun FlatWorld.growBambooShoot(pos: BlockPos, changed: MutableCollection<BlockPos>) {
    val bamboo = blocks.defaultOf(BAMBOO) ?: return
    set(pos, bamboo)
    blocks.find(BAMBOO, bamboo.properties + (BAMBOO_LEAVES to "small_leaves"))?.let { set(pos.above, it) }
    changed += listOf(pos, pos.above)
}

/**
 * Grows the bamboo whose top, [state], is at [pos], [height] tall, by a
 * block, as the game does: the new top gets small leaves, or large ones
 * over leaves, which then go small below it and bare below that; it's
 * thick if the stalk under it is; and it stops the stalk growing, at the
 * most height, or now and then from eleven up. Notes where blocks changed
 * in [changed].
 */
internal fun FlatWorld.growBamboo(pos: BlockPos, state: BlockState, height: Int, random: Random, changed: MutableCollection<BlockPos>) {
    val below = stateAt(pos.below)
    val twoBelow = stateAt(pos.below.below)
    var leaves = "small_leaves"
    if (below?.name == BAMBOO && below.properties[BAMBOO_LEAVES] != "no_leaves") {
        leaves = "large_leaves"
        if (twoBelow?.name == BAMBOO) {
            blocks.find(BAMBOO, below.properties + (BAMBOO_LEAVES to "small_leaves"))?.let { set(pos.below, it); changed += pos.below }
            blocks.find(BAMBOO, twoBelow.properties + (BAMBOO_LEAVES to "no_leaves"))?.let { set(pos.below.below, it); changed += pos.below.below }
        }
    }
    val thick = state.properties[BAMBOO_THICKNESS] == "thick" || twoBelow?.name == BAMBOO
    val done = height == BAMBOO_HEIGHT - 1 || (height >= 11 && random.nextFloat() < 0.25f)
    val grown = mapOf(BAMBOO_LEAVES to leaves, BAMBOO_THICKNESS to if (thick) "thick" else "thin", BAMBOO_DONE to bit(done))
    blocks.find(BAMBOO, (blocks.defaultOf(BAMBOO)?.properties ?: return) + grown)?.let { set(pos.above, it); changed += pos.above }
}

/** Whether the block at [pos] is a water source, which kelp grows into. */
internal fun FlatWorld.isWaterSource(pos: BlockPos) = stateAt(pos)?.let { it.liquid == Liquid.WATER && it.liquidDepth == 0 } == true

/** Kelp growing, from the top of the plant, a block up into the water now and then, till it's grown all it can. */
private fun FlatWorld.growKelp(pos: BlockPos, state: BlockState, growing: Growing) {
    val age = state.number(KELP_AGE)
    if (age >= MAX_VINE_AGE || growing.random.nextDouble() >= KELP_CHANCE || !isWaterSource(pos.above)) return
    put(pos.above, KELP, growing, KELP_AGE to age + 1)
}

/** A sweet berry bush growing a stage, a fifth of the time, in light enough, to full berries. */
private fun FlatWorld.growBerries(pos: BlockPos, state: BlockState, growing: Growing) {
    val growth = state.number(GROWTH)
    if (growth < MAX_BERRY_GROWTH && growing.random.nextInt(5) == 0 && lightAt(pos.above) >= GROWING_LIGHT) grow(pos, state, growing, GROWTH to growth + 1)
}

internal const val MAX_BERRY_GROWTH = 3
internal const val MAX_COCOA_AGE = 2
internal const val MAX_WART_AGE = 3

/** A cocoa pod growing a stage, a fifth of the time. */
private fun FlatWorld.growCocoa(pos: BlockPos, state: BlockState, growing: Growing) {
    val age = state.number(AGE)
    if (age < MAX_COCOA_AGE && growing.random.nextInt(5) == 0) grow(pos, state, growing, AGE to age + 1)
}

/** Nether wart growing a stage, a tenth of the time. */
private fun FlatWorld.growWart(pos: BlockPos, state: BlockState, growing: Growing) {
    val age = state.number(AGE)
    if (age < MAX_WART_AGE && growing.random.nextInt(10) == 0) grow(pos, state, growing, AGE to age + 1)
}

/** Cave vines of any kind, with glow berries or not. */
internal val BlockState.isCaveVines get() = name == CAVE_VINES || name == CAVE_VINES_BERRIES_HEAD || name == CAVE_VINES_BERRIES_BODY

/**
 * Cave vines growing down from their end, now and then, a block into the
 * air below, with glow berries now and then, till they've grown all they
 * can. The end they grew from, if it had berries, keeps them along the vine.
 */
private fun FlatWorld.growCaveVines(pos: BlockPos, state: BlockState, growing: Growing) {
    val age = state.number(VINE_AGE)
    if (age >= MAX_VINE_AGE || growing.random.nextDouble() >= VINE_CHANCE || stateAt(pos.below)?.name != AIR) return
    if (state.name == CAVE_VINES_BERRIES_HEAD) blocks.find(CAVE_VINES_BERRIES_BODY, state.properties)?.let { set(pos, it); growing.changed += pos }
    put(pos.below, if (growing.random.nextDouble() < BERRIES_CHANCE) CAVE_VINES_BERRIES_HEAD else CAVE_VINES, growing, VINE_AGE to age + 1)
}

/** The property twisting or weeping vines count their age in, and which way each grows. */
internal fun netherVineAge(name: String) = if (name == TWISTING_VINES) "twisting_vines_age" else "weeping_vines_age"
internal fun netherVineGrowth(name: String) = if (name == TWISTING_VINES) Face.UP else Face.DOWN

/** Twisting vines growing up from their end, or weeping vines down, now and then, a block into the air, till they've grown all they can. */
private fun FlatWorld.growNetherVines(pos: BlockPos, state: BlockState, growing: Growing) {
    val property = netherVineAge(state.name)
    val age = state.number(property)
    val into = pos.side(netherVineGrowth(state.name))
    if (age >= MAX_VINE_AGE || growing.random.nextDouble() >= VINE_CHANCE || stateAt(into)?.name != AIR) return
    put(into, state.name, growing, property to age + 1)
}

/**
 * Grows the twisting or weeping vines whose end is at [end], [state], by
 * [length] blocks at the most, into the air beyond it. Notes where they
 * grew in [changed].
 */
internal fun FlatWorld.growNetherVines(end: BlockPos, state: BlockState, length: Int, changed: MutableCollection<BlockPos>) {
    val property = netherVineAge(state.name)
    var age = state.number(property)
    var at = end
    repeat(length) {
        at = at.side(netherVineGrowth(state.name))
        if (at.y !in MIN_Y..MAX_Y || stateAt(at)?.name != AIR) return
        age = minOf(age + 1, MAX_VINE_AGE)
        set(at, blocks.find(state.name, state.properties + (property to age)) ?: return)
        changed += at
    }
}

/** A mangrove propagule hanging from leaves growing a stage, or one planted growing into a mangrove a seventh of the time. */
private fun FlatWorld.growPropagule(pos: BlockPos, state: BlockState, growing: Growing) {
    val stage = state.number(PROPAGULE_STAGE)
    if (state.properties[HANGING] == 1.toByte()) {
        if (stage < MAX_PROPAGULE_STAGE) grow(pos, state, growing, PROPAGULE_STAGE to stage + 1)
    } else if (growing.random.nextInt(7) == 0) {
        growTree(pos, state, growing.random)?.let(growing.changed::addAll)
    }
}

/** A sapling growing a stage a seventh of the time, in light enough above it: first older, then into its tree, if there's room. */
private fun FlatWorld.growSapling(pos: BlockPos, state: BlockState, growing: Growing) {
    if (growing.random.nextInt(7) == 0 && internalLightAt(pos.above) >= GROWING_LIGHT) advanceSapling(pos, state, growing.random, growing.changed)
}

/**
 * The sapling [state] at [pos] a stage on: older, or once older, into its
 * tree, if there's room. Notes where blocks changed in [changed]. Returns
 * whether anything did.
 */
internal fun FlatWorld.advanceSapling(pos: BlockPos, state: BlockState, random: Random, changed: MutableCollection<BlockPos>): Boolean {
    if (state.properties[SAPLING_AGE] == 0.toByte()) {
        set(pos, blocks.find(state.name, state.properties + (SAPLING_AGE to bit(true))) ?: return false)
        changed += pos
        return true
    }
    return growTree(pos, state, random)?.let(changed::addAll) != null
}

/**
 * Whether grass or mycelium can be at [pos]: unless the block above stops
 * all light, or is liquid full deep. A single layer of snow doesn't count.
 */
private fun FlatWorld.grassCanBe(pos: BlockPos): Boolean {
    val above = stateAt(pos.above) ?: return true
    if (above.name == SNOW_LAYER && above.number("height") == 0) return true
    val liquid = liquidHere(pos.above)
    if (liquid != null && (liquid.depth == 0 || liquid.depth >= 8)) return false
    return dampening(above) < MAX_LIGHT
}

/**
 * Grass or mycelium dying back to dirt under a block that stops all light,
 * or spreading, with light enough above it, to dirt with room above in
 * four places tried at random, from a block either side to three below
 * and one above.
 */
private fun FlatWorld.spreadGrass(pos: BlockPos, state: BlockState, growing: Growing) {
    if (!grassCanBe(pos)) return put(pos, DIRT, growing)
    if (internalLightAt(pos.above) < GROWING_LIGHT) return
    val random = growing.random
    repeat(4) {
        val at = BlockPos(pos.x + random.nextInt(-1, 2), pos.y + random.nextInt(-3, 2), pos.z + random.nextInt(-1, 2))
        if (stateAt(at)?.name == DIRT && grassCanBe(at) && !isWet(at.above)) put(at, state.name, growing)
    }
}

/**
 * Farmland wetting through with water up to four blocks off, at its level
 * or one up, or with rain falling on it, or drying a step at a time
 * without, till it's dry, when it goes back to dirt unless something
 * grows on it.
 */
private fun FlatWorld.moisten(pos: BlockPos, state: BlockState, growing: Growing) {
    val moisture = state.number(MOISTURE)
    when {
        waterNear(pos) || (weather.raining && precipitationAt(pos.above) == Precipitation.RAIN && topOf(pos.x, pos.z).y <= pos.above.y) ->
            if (moisture < 7) grow(pos, state, growing, MOISTURE to 7)
        moisture > 0 -> grow(pos, state, growing, MOISTURE to moisture - 1)
        stateAt(pos.above)?.name !in ON_FARMLAND -> put(pos, DIRT, growing)
    }
}

/** Whether there's water within four blocks of the farmland at [pos], at its level or one up. */
private fun FlatWorld.waterNear(pos: BlockPos): Boolean {
    for (dx in -4..4) for (dz in -4..4) for (dy in 0..1) if (isWet(BlockPos(pos.x + dx, pos.y + dy, pos.z + dz))) return true
    return false
}

/** How far, at the least, something has to fall onto farmland to have any chance of trampling it. */
private const val TRAMPLING_FALL = 0.5f

/**
 * Something landing on the block at [pos], having fallen [distance]
 * blocks: farmland is trampled back to dirt, by a chance of the distance
 * less half a block, so always from a block and a half up, unless growth
 * is switched off. Returns whether
 * it was.
 */
fun FlatWorld.landOn(pos: BlockPos, distance: Float, random: Random = Random.Default): Boolean {
    if (!features.growth || stateAt(pos)?.name != FARMLAND || random.nextFloat() >= distance - TRAMPLING_FALL) return false
    set(pos, blocks.defaultOf(DIRT) ?: return false)
    return true
}

/** Farmland with a whole block over it can't stay farmland, and goes back to dirt. */
internal fun FlatWorld.farmlandCovered(pos: BlockPos) = stateAt(pos.above)?.isFullCube == true

/**
 * Leaves of a tree that something beside changed looking again for wood:
 * those with wood within seven steps through leaves stay, and stop
 * looking, and the rest decay, dropping what leaves drop.
 */
private fun FlatWorld.decay(pos: BlockPos, state: BlockState, growing: Growing) {
    if (state.properties[LEAF_UPDATE] != 1.toByte()) return
    if (woodNear(pos)) return grow(pos, state, growing, LEAF_UPDATE to bit(false))
    breakAt(pos, state, growing.broken)
    growing.changed += pos
}

/** Whether wood is within [LEAF_REACH] steps of the leaves at [pos], through other leaves. */
private fun FlatWorld.woodNear(pos: BlockPos): Boolean {
    val reached = hashSetOf(pos)
    var edge = listOf(pos)
    repeat(LEAF_REACH) {
        edge = edge.flatMap { at -> Face.entries.map(at::side) }.filter { reached.add(it) }.filter { at ->
            val state = stateAt(at) ?: return@filter false
            if (state.holdsLeaves) return true
            LEAF_UPDATE in state.properties && PERSISTENT in state.properties
        }
    }
    return false
}

/**
 * Marks the leaves of trees beside [changed] to look again for wood the
 * next time a random tick lands on them, as anything beside leaves
 * changing does. Leaves that changed themselves, as a tree's own do as it
 * grows, aren't. Returns where they were.
 */
internal fun FlatWorld.wakeLeaves(changed: Collection<BlockPos>): List<BlockPos> {
    val woken = mutableListOf<BlockPos>()
    val itself = changed.toHashSet()
    for (at in changed.flatMap { pos -> Face.entries.map(pos::side) }.distinct()) {
        if (at in itself) continue
        val leaves = stateAt(at)?.takeIf { it.fallsWithoutWood && it.properties[LEAF_UPDATE] == 0.toByte() } ?: continue
        set(at, blocks.find(leaves.name, leaves.properties + (LEAF_UPDATE to bit(true))) ?: continue)
        woken += at
    }
    return woken
}

/** Ice melting into water, with blocks' light beside it brighter than 11. The sky's doesn't melt it. */
private fun FlatWorld.meltIce(pos: BlockPos, state: BlockState, growing: Growing) {
    if (Face.entries.none { blockLightAt(pos.side(it)) >= MELTING_LIGHT }) return
    set(pos, liquidState(Liquid.WATER, 0))
    growing.changed += pos
}

/** Snow melting a layer at a time, in blocks' light brighter than 11, the last of it breaking. Snow over a plant stays. */
private fun FlatWorld.meltSnow(pos: BlockPos, state: BlockState, growing: Growing) {
    if (state.properties["covered_bit"] == 1.toByte() || blockLightAt(pos) < MELTING_LIGHT) return
    val height = state.number("height")
    if (height > 0) return grow(pos, state, growing, "height" to height - 1)
    breakAt(pos, state, growing.broken)
    growing.changed += pos
}

/**
 * Copper weathering a stage, now and then, as the game does: copper less
 * weathered within four blocks holds it back altogether, and copper as
 * weathered slows it, so a group of copper weathers evenly from its edges.
 * A door weathers by its lower half, and both halves go together.
 */
private fun FlatWorld.weather(pos: BlockPos, state: BlockState, growing: Growing) {
    if (state.properties[UPPER_BLOCK] == 1.toByte() || growing.random.nextFloat() >= WEATHERING_CHANCE) return
    val stage = weathering(state) ?: return
    val next = moreWeathered(state) ?: return
    var same = 0
    var further = 0
    for (dx in -WEATHERING_REACH..WEATHERING_REACH) for (dy in -WEATHERING_REACH..WEATHERING_REACH) for (dz in -WEATHERING_REACH..WEATHERING_REACH) {
        if (abs(dx) + abs(dy) + abs(dz) !in 1..WEATHERING_REACH) continue
        val other = stateAt(BlockPos(pos.x + dx, pos.y + dy, pos.z + dz))?.let(::weathering) ?: continue
        when {
            other < stage -> return
            other > stage -> further++
            else -> same++
        }
    }
    val ratio = (further + 1f) / (further + same + 1f)
    if (growing.random.nextFloat() >= ratio * ratio * if (stage == 0) 0.75f else 1f) return
    set(pos, next)
    growing.changed += pos
    val upper = state.otherHalf(pos)?.takeIf { stateAt(it)?.name?.let(::copperKind) == copperKind(state.name) } ?: return
    stateAt(upper)?.let { half -> blocks.find(next.name, half.properties) }?.let { set(upper, it); growing.changed += upper }
}

/**
 * Budding amethyst growing a bud a fifth of the time, on a side picked at
 * random: in air or still water, or the next stage on of the bud already
 * growing from that side, to a cluster.
 */
private fun FlatWorld.growBud(pos: BlockPos, state: BlockState, growing: Growing) {
    if (growing.random.nextInt(5) != 0) return
    val face = Face.entries.random(growing.random)
    val at = pos.side(face)
    val there = stateAt(at) ?: return
    val next = when {
        there.name == AIR || isWaterSource(at) -> BUD_STAGES.first()
        there.name in BUD_STAGES && there.properties[BLOCK_FACE] == face.id -> BUD_STAGES.getOrNull(BUD_STAGES.indexOf(there.name) + 1) ?: return
        else -> return
    }
    put(at, next, growing, BLOCK_FACE to face.id)
}

/**
 * A stalactite, by its top block, under a block with water or lava above
 * it: now and then, it drips into the cauldron below its tip, within
 * eleven blocks with nothing in between, a level of water, or a whole
 * cauldron of lava into an empty one; or turns mud above it to clay. Under
 * dripstone with water above, it now and then grows a block longer, or
 * the stalagmite below it a block taller, up to eight long.
 */
private fun FlatWorld.drip(pos: BlockPos, state: BlockState, growing: Growing) {
    if (stateAt(pos.above)?.name == POINTED_DRIPSTONE) return
    val source = pos.above.above
    val sourceState = stateAt(source) ?: return
    val chance = growing.random.nextFloat()
    val tip = tipBelow(pos) ?: return
    val liquid = sourceState.liquid?.takeIf { sourceState.liquidDepth == 0 }
    when {
        sourceState.name == MUD && chance < DRIP_WATER_CHANCE -> put(source, CLAY, growing)
        liquid == Liquid.WATER && chance < DRIP_WATER_CHANCE -> cauldronBelow(tip)?.let { if (rainFills(it, stateAt(it)!!)) growing.changed += it }
        liquid == Liquid.LAVA && chance < DRIP_LAVA_CHANCE -> cauldronBelow(tip)?.let { if (lavaFills(it, stateAt(it)!!)) growing.changed += it }
    }
    if (stateAt(pos.above)?.name != DRIPSTONE_BLOCK || liquid != Liquid.WATER || growing.random.nextFloat() >= DRIPSTONE_GROWTH_CHANCE) return
    if (tip.y < pos.y - DRIPSTONE_LENGTH || liquidHere(tip) != null) return
    if (growing.random.nextBoolean()) growStalactite(tip, state, growing) else growStalagmite(tip, state, growing)
}

/** The tip of the stalactite whose top is at [pos], or null if it's longer than a cauldron catches drips from. */
private fun FlatWorld.tipBelow(pos: BlockPos): BlockPos? {
    var at = pos
    repeat(DRIP_REACH) {
        if (stateAt(at.below)?.let { it.name == POINTED_DRIPSTONE && it.properties[HANGING] == 1.toByte() } != true) return at
        at = at.below
    }
    return null
}

/** The cauldron below the stalactite tip at [tip], within reach of its drips, with only air between, or null for none. */
private fun FlatWorld.cauldronBelow(tip: BlockPos): BlockPos? {
    var at = tip
    repeat(DRIP_REACH) {
        at = at.below
        val state = stateAt(at) ?: return null
        if (state.isCauldron) return at
        if (state.name != AIR) return null
    }
    return null
}

/** The stalactite with its tip at [tip] growing a block longer, into the air below it. */
private fun FlatWorld.growStalactite(tip: BlockPos, state: BlockState, growing: Growing) {
    if (stateAt(tip.below)?.name == AIR) put(tip.below, state.name, growing, HANGING to bit(true))
}

/**
 * The stalagmite under the stalactite with its tip at [tip] growing a
 * block taller, or starting on the floor, within ten blocks with only air
 * between. Kore doesn't join the two where they'd meet.
 */
private fun FlatWorld.growStalagmite(tip: BlockPos, state: BlockState, growing: Growing) {
    var at = tip.below
    repeat(10) {
        if (stateAt(at)?.name != AIR) return
        val below = stateAt(at.below) ?: return
        when {
            below.name == AIR -> at = at.below
            (below.name == state.name && below.properties[HANGING] == 0.toByte()) || below.hasFullFace(Face.UP) -> {
                put(at, state.name, growing, HANGING to bit(false))
                return
            }
            else -> return
        }
    }
}

/**
 * Whether [block], one that stands on the ground, can stand on [ground]:
 * crops and stems on farmland, sugar cane on sand or the like, or more
 * cane, cactus on sand or more cactus, nether wart on soul sand, and
 * berries and saplings on dirt of some kind, and a planted mangrove
 * propagule on clay too. Lily pads and frogspawn float on still water,
 * and lily pads sit on ice too. Null for a plant that stands on anything.
 */
internal fun standsOn(block: BlockState, ground: BlockState): Boolean? = when {
    block.floatsOnWater -> (ground.liquid == Liquid.WATER && ground.liquidDepth == 0) ||
        (block.name == WATERLILY && (ground.name == ICE || ground.name == FROSTED_ICE))
    block.isSapling -> ground.name in DIRTS
    block.name == MANGROVE_PROPAGULE && block.properties[HANGING] == 0.toByte() -> ground.name in DIRTS || ground.name == CLAY
    block.name in CROPS || block.name in STEM_FRUIT || block.name == TORCHFLOWER_CROP -> ground.name == FARMLAND
    block.name == PITCHER_CROP -> if (block.properties[UPPER_BLOCK] == 1.toByte()) ground.name == PITCHER_CROP else ground.name == FARMLAND
    block.name == REEDS -> ground.name == REEDS || ground.name in CANE_SOIL
    block.name == CACTUS -> ground.name == CACTUS || ground.name in CACTUS_SOIL
    block.name == NETHER_WART -> ground.name == SOUL_SAND
    block.name == SWEET_BERRY_BUSH -> ground.name in DIRTS
    else -> null
}

/** Whether the sugar cane at [pos] has water beside the ground it grows on, or grows on more cane. */
internal fun FlatWorld.caneWatered(pos: BlockPos): Boolean {
    if (stateAt(pos.below)?.name == REEDS) return true
    return AROUND.any { side -> pos.below.side(side).let { isWet(it) || stateAt(it)?.name == FROSTED_ICE } }
}

/** The sounds of berries picked off a sweet berry bush, and off cave vines. */
private const val BERRIES_PICKED = "block.sweet_berry_bush.pick"
private const val GLOW_BERRIES_PICKED = "pick_berries.cave_vines"

/** Whether [state] is cave vines with glow berries on them. */
internal val BlockState.hasGlowBerries get() = name == CAVE_VINES_BERRIES_HEAD || name == CAVE_VINES_BERRIES_BODY

/**
 * Picks what grows on the block at [pos], [state], by hand, if it's ripe:
 * the berries off a sweet berry bush with any, two or three full or one or
 * two with some, which goes back to bare, or the glow berries off cave
 * vines, which keep growing without. They drop beside it, made with
 * [items], with the sound of their picking. Null for anything else.
 */
fun FlatWorld.pick(pos: BlockPos, state: BlockState, items: ItemStacks, random: Random = Random.Default): ItemUsedOn? = when {
    state.name == SWEET_BERRY_BUSH -> {
        val growth = state.number(GROWTH)
        if (growth < MAX_BERRY_GROWTH - 1) {
            null
        } else {
            val count = if (growth >= MAX_BERRY_GROWTH) random.nextInt(2, 4) else random.nextInt(1, 3)
            blocks.find(state.name, state.properties + (GROWTH to 1))?.let { set(pos, it) }
            ItemUsedOn(listOf(pos), BlockSound(pos, BERRIES_PICKED, -1), dropped = listOfNotNull(items.of(SWEET_BERRIES, count)), dropAt = pos)
        }
    }
    state.hasGlowBerries -> {
        blocks.find(CAVE_VINES, state.properties)?.let { set(pos, it) }
        ItemUsedOn(listOf(pos), BlockSound(pos, GLOW_BERRIES_PICKED, -1), dropped = listOfNotNull(items.of("minecraft:glow_berries")), dropAt = pos)
    }
    else -> null
}
