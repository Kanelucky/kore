package org.koremc.world

import org.koremc.registry.AIR
import org.koremc.registry.BlockState
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemStacks
import kotlin.math.abs
import kotlin.random.Random

/** The particles of a plant growing, as bone meal makes it, over the block, or over an area for data 1, as on grass. */
private const val GROWTH_PARTICLES = 2005

private const val SHORT_GRASS = "minecraft:short_grass"
private const val TALL_GRASS = "minecraft:tall_grass"
private const val FERN = "minecraft:fern"
private const val LARGE_FERN = "minecraft:large_fern"
private const val SHORT_DRY_GRASS = "minecraft:short_dry_grass"
private const val TALL_DRY_GRASS = "minecraft:tall_dry_grass"
private const val DANDELION = "minecraft:dandelion"
private const val POPPY = "minecraft:poppy"
private const val PALE_HANGING_MOSS = "minecraft:pale_hanging_moss"
private const val SMALL_DRIPLEAF = "minecraft:small_dripleaf_block"
private const val HANGING_ROOTS = "minecraft:hanging_roots"
private const val MANGROVE_LEAVES = "minecraft:mangrove_leaves"
private const val BIG_DRIPLEAF_HEAD = "big_dripleaf_head"
private const val DRIPLEAF_TILT = "big_dripleaf_tilt"
private const val MOSS_TIP = "tip"

/** Flowers two blocks tall, which drop another of themselves for bone meal. */
private val TALL_FLOWERS = setOf("minecraft:sunflower", "minecraft:lilac", "minecraft:rose_bush", "minecraft:peony")

/** Flowers a block tall that bone meal spreads more of over the grass around them, as Bedrock has it. */
private val SPREADING_FLOWERS = setOf(
    DANDELION, POPPY, "minecraft:blue_orchid", "minecraft:allium", "minecraft:azure_bluet", "minecraft:red_tulip", "minecraft:orange_tulip",
    "minecraft:white_tulip", "minecraft:pink_tulip", "minecraft:oxeye_daisy", "minecraft:cornflower", "minecraft:lily_of_the_valley",
)

/** Plants of which up to four lie in one space, which bone meal adds one to, or drops one of once there are four. */
private val PETALS = setOf("minecraft:pink_petals", "minecraft:wildflowers")
private const val MOST_PETALS = 3

/** Bushes, which bone meal plants another of beside them. */
private val BUSHES = setOf("minecraft:bush", "minecraft:firefly_bush")

/** What dry grass grows on, besides dirt: sand and terracotta. */
private val DRY_SOIL = setOf("minecraft:sand", "minecraft:red_sand", "minecraft:suspicious_sand")

/** How much a bone meal grows crops and stems, from the fewest stages to the most. */
private val CROP_BOOST = 2..5

/** How far around grass bone meal spreads plants, in steps from the block, and how many it tries. */
private const val GRASS_STEPS = 3
private const val GRASS_TRIES = 128

/** How far around a flower bone meal spreads more of it, across and up or down, and how many it tries. */
private const val FLOWER_REACH = 3
private const val FLOWER_TRIES = 8

/** How tall a small dripleaf grows into a big one with bone meal, from the least to the most. */
private val DRIPLEAF_HEIGHT = 2..5

/** The azaleas, which bone meal grows into azalea trees. */
private val AZALEAS = setOf("minecraft:azalea", "minecraft:flowering_azalea")

private const val GLOW_LICHEN = "minecraft:glow_lichen"
internal const val SEAGRASS = "minecraft:seagrass"
private const val SEAGRASS_TYPE = "sea_grass_type"
private const val SEAGRASS_SHORT = "default"
internal const val SEA_PICKLE = "minecraft:sea_pickle"
internal const val PICKLE_DEAD = "dead_bit"
private const val PICKLES = "cluster_count"
private const val MOST_PICKLES = 3

/** How far around them, counting across, sea pickles spread, and how unlikely they are to spread to each spot in reach. */
private const val PICKLE_REACH = 2
private const val PICKLE_ONE_IN = 6

/** Living coral blocks, which sea pickles spread over. */
private val LIVING_CORAL_BLOCKS = listOf("tube", "brain", "bubble", "fire", "horn").mapTo(HashSet()) { "minecraft:${it}_coral_block" }

/** Coral and coral fans, which bone meal on the seabed grows now and then, rather than seagrass. */
private val SEABED_CORALS = listOf("tube", "brain", "bubble", "fire", "horn").flatMap { listOf("minecraft:${it}_coral", "minecraft:${it}_coral_fan") }

/** The seabed bone meal grows seagrass on, as Bedrock has it, and how it plants it: how many tries, and how unlikely coral, or seagrass growing tall, is on each. */
private val SEABED = setOf("dirt", "coarse_dirt", "sand", "red_sand", "gravel", "clay").mapTo(HashSet()) { "minecraft:$it" }
private const val SEABED_TRIES = 128
private const val CORAL_ONE_IN = 4
private const val TALL_SEAGRASS_ONE_IN = 10

/** Blocks bone meal spreads more of over what's around them, whose particles cover an area. */
private val SPREADERS = setOf(MOSS_BLOCK, PALE_MOSS_BLOCK, GLOW_LICHEN, SEA_PICKLE)

/** The chance a bone meal grows a sapling a stage, or an azalea or propagule into a tree, and a mushroom or fungus huge. */
private const val TREE_CHANCE = 0.45
private const val HUGE_CHANCE = 0.4

/**
 * Bone meal used on the block at [pos], [state], as Bedrock has it, if it
 * grows it. Crops and stems grow two to five stages, and torchflower and
 * pitcher crops, berries, cocoa and hanging propagules one. Sugar cane
 * grows three tall, kelp a block, and bamboo a block or two. Grass grows
 * tall, and ferns large. Grass blocks sprout grass and flowers around them,
 * and small flowers more of themselves; tall flowers, and petals four to a
 * block, drop another. Cave vines grow glow berries, nether vines and pale
 * hanging moss grow on, and bushes and dry grass spread beside them. A big
 * dripleaf grows a block taller, and a small one into a big one. Rooted
 * dirt grows hanging roots below it, and mangrove leaves a propagule.
 * Saplings grow a stage now and then, azaleas and planted propagules into
 * trees, and mushrooms and fungi on their own ground into huge ones, if
 * there's room: a bone meal that doesn't bring that about is still used
 * up, but one that would, but for the room, isn't. Nylium sprouts roots
 * and fungi round it, and netherrack beside nylium turns to it. Moss
 * spreads a patch of itself, glow lichen spreads by a face, seagrass in
 * still water grows tall, and sea pickles on living coral spread over the
 * coral around them.
 *
 * Returns what it did, with bone meal's particles, using up a bone meal,
 * and what dropped, made with [items], or null for a block bone meal does
 * nothing for, as it is, or with growth switched off. Chance comes from
 * [random].
 */
fun FlatWorld.fertilize(pos: BlockPos, state: BlockState, items: ItemStacks, random: Random = Random.Default): ItemUsedOn? {
    if (!features.growth) return null
    val changed = mutableListOf<BlockPos>()
    val dropped = mutableListOf<ItemStack>()
    val name = state.name
    val grew = when {
        name in CROPS -> state.number(GROWTH) < MAX_CROP_GROWTH &&
            change(pos, state, changed, GROWTH to minOf(MAX_CROP_GROWTH, state.number(GROWTH) + random.nextInt(CROP_BOOST.first, CROP_BOOST.last + 1)))
        name == TORCHFLOWER_CROP -> {
            growTorchflower(pos, state, changed)
            true
        }
        name == PITCHER_CROP -> {
            val lower = if (state.properties[UPPER_BLOCK] == 1.toByte()) pos.below else pos
            stateAt(lower)?.takeIf { it.name == PITCHER_CROP }?.let { growPitcher(lower, it, changed) } == true
        }
        name in STEM_FRUIT -> {
            val growth = state.number(GROWTH)
            if (growth < MAX_CROP_GROWTH) growStem(pos, state, minOf(MAX_CROP_GROWTH, growth + random.nextInt(CROP_BOOST.first, CROP_BOOST.last + 1)), changed)
            growth < MAX_CROP_GROWTH
        }
        name == SWEET_BERRY_BUSH -> state.number(GROWTH) < MAX_BERRY_GROWTH && change(pos, state, changed, GROWTH to state.number(GROWTH) + 1)
        name == COCOA -> state.number(AGE) < MAX_COCOA_AGE && change(pos, state, changed, AGE to state.number(AGE) + 1)
        name == REEDS -> growCaneUp(pos, changed)
        name == KELP -> growKelpUp(pos, changed)
        name == BAMBOO -> growBambooUp(pos, random, changed)
        name == BAMBOO_SAPLING -> {
            val room = stateAt(pos.above)?.name == AIR
            if (room) growBambooShoot(pos, changed)
            room
        }
        name == SHORT_GRASS -> growTall(pos, TALL_GRASS, changed)
        name == FERN -> growTall(pos, LARGE_FERN, changed)
        name == SHORT_DRY_GRASS -> blocks.defaultOf(TALL_DRY_GRASS)?.let { set(pos, it); changed += pos } != null
        name in TALL_FLOWERS -> items.of(name)?.let(dropped::add) == true
        name in SPREADING_FLOWERS -> spreadFlower(pos, state, random, changed)
        name == GRASS_BLOCK -> {
            val room = stateAt(pos.above)?.name == AIR
            if (room) sprout(pos, random, changed)
            room
        }
        name == CAVE_VINES -> {
            val berries = if (stateAt(pos.below)?.isCaveVines == true) CAVE_VINES_BERRIES_BODY else CAVE_VINES_BERRIES_HEAD
            blocks.find(berries, state.properties)?.let { set(pos, it); changed += pos } != null
        }
        name == TWISTING_VINES || name == WEEPING_VINES -> growNetherVinesOn(pos, state, random, changed)
        name == PALE_HANGING_MOSS -> growMossDown(pos, state, changed)
        name in PETALS -> if (state.number(GROWTH) < MOST_PETALS) change(pos, state, changed, GROWTH to state.number(GROWTH) + 1) else items.of(name)?.let(dropped::add) == true
        name == BIG_DRIPLEAF -> growDripleaf(pos, changed)
        name == SMALL_DRIPLEAF -> growSmallDripleaf(pos, state, random, changed)
        name == ROOTED_DIRT -> hangBelow(pos, HANGING_ROOTS, changed)
        name == MANGROVE_LEAVES -> hangBelow(pos, MANGROVE_PROPAGULE, changed, HANGING to bit(true))
        name == MANGROVE_PROPAGULE && state.properties[HANGING] == 1.toByte() -> state.number(PROPAGULE_STAGE) < MAX_PROPAGULE_STAGE &&
            change(pos, state, changed, PROPAGULE_STAGE to state.number(PROPAGULE_STAGE) + 1)
        state.isSapling -> canGrowTree(pos, state) && (random.nextDouble() >= TREE_CHANCE || advanceSapling(pos, state, random, changed))
        name == MANGROVE_PROPAGULE || name in AZALEAS -> liquidHere(pos.above) == null &&
            (random.nextDouble() >= TREE_CHANCE || growTree(pos, state, random)?.let(changed::addAll) != null)
        state.isMushroom -> stateAt(pos.below)?.name.let { it in DIRTS || it in setOf(MYCELIUM, "minecraft:podzol", CRIMSON_NYLIUM, WARPED_NYLIUM) } &&
            (random.nextDouble() >= HUGE_CHANCE || growHugeMushroom(pos, state, random)?.let(changed::addAll) != null)
        name == CRIMSON_FUNGUS || name == WARPED_FUNGUS -> onOwnNylium(pos, state) &&
            (random.nextDouble() >= HUGE_CHANCE || growHugeFungus(pos, state, random)?.let(changed::addAll) != null)
        name == CRIMSON_NYLIUM || name == WARPED_NYLIUM -> {
            val room = stateAt(pos.above)?.name == AIR
            if (room) sproutNylium(pos, state, random, changed)
            room
        }
        name == NETHERRACK -> nyliumBeside(pos).takeIf { it.isNotEmpty() }?.let { change(pos, blocks.defaultOf(it.random(random)) ?: return null, changed) } == true
        // Moss with air above it, and a sea pickle on living coral, take the bone meal however far they spread.
        name == MOSS_BLOCK || name == PALE_MOSS_BLOCK -> (stateAt(pos.above)?.name == AIR).also { if (it) changed += spreadMoss(pos, random) }
        name == SEA_PICKLE -> (state.properties[PICKLE_DEAD] == bit(false) && stateAt(pos.below)?.name in LIVING_CORAL_BLOCKS).also {
            if (it) spreadPickles(pos, state, random, changed)
        }
        name == GLOW_LICHEN -> spreadLichen(pos, state, random, changed)
        name == SEAGRASS -> state.properties[SEAGRASS_TYPE] == SEAGRASS_SHORT && isWaterSource(pos.above) && growTallSeagrass(pos, changed)
        name in BUSHES -> spreadBeside(pos, name, changed, random) { it.name in DIRTS }
        name == TALL_DRY_GRASS -> spreadBeside(pos, SHORT_DRY_GRASS, changed, random) { it.name in DIRTS || it.name in DRY_SOIL || it.name.endsWith("terracotta") }
        else -> false
    }
    if (!grew) return null
    val area = name == GRASS_BLOCK || name in SPREADING_FLOWERS || name == CRIMSON_NYLIUM || name == WARPED_NYLIUM || name in SPREADERS
    return ItemUsedOn(
        changed, held = HeldAfter.USED_UP, dropped = dropped, dropAt = pos,
        events = listOf(WorldEvent(pos, GROWTH_PARTICLES, if (area) 1 else 0, centred = false)),
    )
}

/** Puts [state] at [pos] with [changes] to its properties, noting where in [changed]. Returns whether the game has that state. */
private fun FlatWorld.change(pos: BlockPos, state: BlockState, changed: MutableCollection<BlockPos>, vararg changes: Pair<String, Any>): Boolean {
    set(pos, blocks.find(state.name, state.properties + changes) ?: return false)
    changed += pos
    return true
}

/** The top of the column of [name] that [pos] is in. */
private fun FlatWorld.topOfColumn(pos: BlockPos, name: String): BlockPos {
    var top = pos
    while (stateAt(top.above)?.name == name) top = top.above
    return top
}

/** Grows the sugar cane at [pos] three tall, as far as there's air above it. Returns whether it grew. */
private fun FlatWorld.growCaneUp(pos: BlockPos, changed: MutableCollection<BlockPos>): Boolean {
    var top = topOfColumn(pos, REEDS)
    var height = heightBelow(top, REEDS, CANE_HEIGHT)
    val cane = blocks.defaultOf(REEDS) ?: return false
    val before = changed.size
    while (height < CANE_HEIGHT && stateAt(top.above)?.name == AIR) {
        top = top.above
        set(top, cane)
        changed += top
        height++
    }
    return changed.size > before
}

/** Grows the kelp that [pos] is part of a block up from its top, into the water above, if it can grow any more. Returns whether it grew. */
private fun FlatWorld.growKelpUp(pos: BlockPos, changed: MutableCollection<BlockPos>): Boolean {
    val top = topOfColumn(pos, KELP)
    val kelp = stateAt(top) ?: return false
    if (kelp.number(KELP_AGE) >= MAX_VINE_AGE || !isWaterSource(top.above)) return false
    set(top.above, blocks.find(KELP, kelp.properties + (KELP_AGE to kelp.number(KELP_AGE) + 1)) ?: return false)
    changed += top.above
    return true
}

/** Grows the bamboo that [pos] is part of by a block or two from its top, while it still grows and there's air above it. Returns whether it grew. */
private fun FlatWorld.growBambooUp(pos: BlockPos, random: Random, changed: MutableCollection<BlockPos>): Boolean {
    var top = topOfColumn(pos, BAMBOO)
    var height = heightBelow(top, BAMBOO, BAMBOO_HEIGHT)
    val before = changed.size
    repeat(random.nextInt(1, 3)) {
        val bamboo = stateAt(top) ?: return@repeat
        if (height >= BAMBOO_HEIGHT || bamboo.properties[BAMBOO_DONE] == 1.toByte() || stateAt(top.above)?.name != AIR) return@repeat
        growBamboo(top, bamboo, height, random, changed)
        top = top.above
        height++
    }
    return changed.size > before
}

/** Grows the plant at [pos] into [tall], two blocks tall, if there's air above it. Returns whether it grew. */
private fun FlatWorld.growTall(pos: BlockPos, tall: String, changed: MutableCollection<BlockPos>): Boolean {
    if (stateAt(pos.above)?.name != AIR) return false
    val lower = blocks.find(tall, mapOf(UPPER_BLOCK to bit(false))) ?: return false
    val upper = blocks.find(tall, mapOf(UPPER_BLOCK to bit(true))) ?: return false
    set(pos, lower)
    set(pos.above, upper)
    changed += listOf(pos, pos.above)
    return true
}

/**
 * Spreads more of the flower [state] at [pos] over the grass around it, as
 * Bedrock does: into the air above grass blocks tried at random, now and
 * then a poppy for a dandelion, or the other way round. Returns whether any
 * grew.
 */
private fun FlatWorld.spreadFlower(pos: BlockPos, state: BlockState, random: Random, changed: MutableCollection<BlockPos>): Boolean {
    val before = changed.size
    repeat(FLOWER_TRIES) {
        val at = BlockPos(pos.x + random.nextInt(-FLOWER_REACH, FLOWER_REACH + 1), pos.y + random.nextInt(-1, 2), pos.z + random.nextInt(-FLOWER_REACH, FLOWER_REACH + 1))
        if (stateAt(at)?.name != AIR || stateAt(at.below)?.name != GRASS_BLOCK) return@repeat
        val name = when {
            random.nextDouble() >= 0.1 -> state.name
            state.name == DANDELION -> POPPY
            state.name == POPPY -> DANDELION
            else -> state.name
        }
        set(at, blocks.defaultOf(name) ?: return@repeat)
        changed += at
    }
    return changed.size > before
}

/**
 * Sprouts plants on the grass around the grass block at [pos], as the game
 * does: from above it, tries a short way across the grass at random, each
 * further than the last, three blocks at the most, and where there's air,
 * grows short grass, or now and then a flower; grass already there now and
 * then grows tall.
 */
private fun FlatWorld.sprout(pos: BlockPos, random: Random, changed: MutableCollection<BlockPos>) {
    tries@ for (attempt in 0 until GRASS_TRIES) {
        var at = pos.above
        for (step in 0 until attempt / (GRASS_TRIES / (GRASS_STEPS + 1))) {
            at = BlockPos(at.x + random.nextInt(-1, 2), at.y + (random.nextInt(-1, 2) * random.nextInt(3) / 2), at.z + random.nextInt(-1, 2))
            if (stateAt(at.below)?.name != GRASS_BLOCK || stateAt(at)?.isFullCube == true) continue@tries
        }
        val there = stateAt(at) ?: continue
        if (there.name == SHORT_GRASS && random.nextInt(10) == 0) growTall(at, TALL_GRASS, changed)
        if (there.name != AIR) continue
        val plant = if (random.nextInt(8) == 0) (if (random.nextBoolean()) DANDELION else POPPY) else SHORT_GRASS
        set(at, blocks.defaultOf(plant) ?: continue)
        changed += at
    }
}

/** Grows the twisting or weeping vines that [pos] is part of on from their end, by a few blocks into the air, the more the less likely. Returns whether they grew. */
private fun FlatWorld.growNetherVinesOn(pos: BlockPos, state: BlockState, random: Random, changed: MutableCollection<BlockPos>): Boolean {
    val growing = netherVineGrowth(state.name)
    var end = pos
    while (stateAt(end.side(growing))?.name == state.name) end = end.side(growing)
    if (stateAt(end.side(growing))?.name != AIR) return false
    var length = 0
    var chance = 1.0
    while (random.nextDouble() < chance) {
        chance *= 0.826
        length++
    }
    val before = changed.size
    growNetherVines(end, stateAt(end) ?: return false, length, changed)
    return changed.size > before
}

/** Grows the pale hanging moss that [pos] is part of a block down from its tip, into the air below. Returns whether it grew. */
private fun FlatWorld.growMossDown(pos: BlockPos, state: BlockState, changed: MutableCollection<BlockPos>): Boolean {
    var tip = pos
    while (stateAt(tip.below)?.name == state.name) tip = tip.below
    if (stateAt(tip.below)?.name != AIR) return false
    set(tip.below, blocks.find(state.name, state.properties + (MOSS_TIP to bit(true))) ?: return false)
    stateAt(tip)?.let { blocks.find(it.name, it.properties + (MOSS_TIP to bit(false))) }?.let { set(tip, it) }
    changed += listOf(tip, tip.below)
    return true
}

/** Whether a big dripleaf can grow into [pos]: air, or still water. */
private fun FlatWorld.dripleafFits(pos: BlockPos) = pos.y <= MAX_Y && (stateAt(pos)?.name == AIR || isWaterSource(pos))

/** Grows the big dripleaf that [pos] is part of a block taller, if there's room above its leaf, which goes up a block, leaving stem. Returns whether it grew. */
private fun FlatWorld.growDripleaf(pos: BlockPos, changed: MutableCollection<BlockPos>): Boolean {
    val leaf = topOfColumn(pos, BIG_DRIPLEAF)
    val state = stateAt(leaf) ?: return false
    if (!dripleafFits(leaf.above)) return false
    val stem = blocks.find(BIG_DRIPLEAF, state.properties + mapOf(BIG_DRIPLEAF_HEAD to bit(false), DRIPLEAF_TILT to "none")) ?: return false
    set(leaf.above, blocks.find(BIG_DRIPLEAF, stem.properties + (BIG_DRIPLEAF_HEAD to bit(true))) ?: return false)
    set(leaf, stem)
    changed += listOf(leaf, leaf.above)
    return true
}

/**
 * Grows the small dripleaf that [pos] is half of into a big dripleaf,
 * facing the same way, two to five blocks tall, as far as there's room.
 * Returns whether it grew.
 */
private fun FlatWorld.growSmallDripleaf(pos: BlockPos, state: BlockState, random: Random, changed: MutableCollection<BlockPos>): Boolean {
    val bottom = if (state.properties[UPPER_BLOCK] == 1.toByte()) pos.below else pos
    val facing = state.properties[CARDINAL] ?: return false
    val wanted = random.nextInt(DRIPLEAF_HEIGHT.first, DRIPLEAF_HEIGHT.last + 1)
    var height = 1
    while (height < wanted && (stateAt(bottom.side(Face.UP, height))?.name == SMALL_DRIPLEAF || dripleafFits(bottom.side(Face.UP, height)))) height++
    for (step in 0 until height) {
        val at = bottom.side(Face.UP, step)
        set(at, blocks.find(BIG_DRIPLEAF, mapOf(CARDINAL to facing, BIG_DRIPLEAF_HEAD to bit(step == height - 1), DRIPLEAF_TILT to "none")) ?: return false)
        changed += at
    }
    // The small dripleaf's top half, if the big one didn't grow that far.
    if (stateAt(bottom.above)?.name == SMALL_DRIPLEAF) {
        set(bottom.above, blocks.stateOf(AIR))
        changed += bottom.above
    }
    return true
}

/** Hangs the block [name] below [pos], with [properties], if there's air there. Returns whether it did. */
private fun FlatWorld.hangBelow(pos: BlockPos, name: String, changed: MutableCollection<BlockPos>, vararg properties: Pair<String, Any>): Boolean {
    if (stateAt(pos.below)?.name != AIR) return false
    val default = blocks.defaultOf(name) ?: return false
    set(pos.below, blocks.find(name, default.properties + properties) ?: return false)
    changed += pos.below
    return true
}

/** Plants [name] beside [pos], on one of its sides picked at random, with air there and ground under it that [grows] on. Returns whether it did. */
private inline fun FlatWorld.spreadBeside(pos: BlockPos, name: String, changed: MutableCollection<BlockPos>, random: Random, grows: (BlockState) -> Boolean): Boolean {
    for (side in AROUND.shuffled(random)) {
        val at = pos.side(side)
        if (stateAt(at)?.name != AIR || stateAt(at.below)?.let(grows) != true) continue
        set(at, blocks.defaultOf(name) ?: return false)
        changed += at
        return true
    }
    return false
}

/**
 * Spreads the glow lichen at [pos], [state], over another face, as bone
 * meal does: from a face it covers, picked at random, toward a side picked
 * at random, onto that side of its own space, onto the same face of the
 * space beside it, or round the edge of the block it's on, whichever comes
 * first with a whole face to cover and room. Returns whether it spread.
 */
private fun FlatWorld.spreadLichen(pos: BlockPos, state: BlockState, random: Random, changed: MutableCollection<BlockPos>): Boolean {
    val faces = facesIn(MULTI_FACE_BITS, state.number(MULTI_FACE))
    for (face in Face.entries.shuffled(random).filter { it in faces }) {
        for (toward in Face.entries.shuffled(random)) {
            if (toward == face || toward == face.opposite || toward in faces) continue
            val beside = pos.side(toward)
            for ((at, onto) in listOf(pos to toward, beside to face, beside.side(face) to toward.opposite)) {
                if (coverWithLichen(at, onto)) {
                    changed += at
                    return true
                }
            }
        }
    }
    return false
}

/**
 * Covers the [face] of the space at [pos] with glow lichen, if the space
 * is air, still water, or lichen not covering that face yet, and the block
 * across that face has a whole side there. Returns whether it did.
 */
private fun FlatWorld.coverWithLichen(pos: BlockPos, face: Face): Boolean {
    if (pos.y !in MIN_Y..MAX_Y) return false
    val there = stateAt(pos) ?: return false
    val covered = when {
        there.name == GLOW_LICHEN -> there.number(MULTI_FACE)
        there.name == AIR || isWaterSource(pos) -> 0
        else -> return false
    }
    val bit = MULTI_FACE_BITS.getValue(face)
    if (covered and bit != 0 || stateAt(pos.side(face))?.hasFullFace(face.opposite) != true) return false
    set(pos, blocks.find(GLOW_LICHEN, mapOf(MULTI_FACE to (covered or bit))) ?: return false)
    return true
}

/** Grows the seagrass at [pos] two blocks tall, into the water above it. Returns whether it grew. */
private fun FlatWorld.growTallSeagrass(pos: BlockPos, changed: MutableCollection<BlockPos>): Boolean {
    val bottom = blocks.find(SEAGRASS, mapOf(SEAGRASS_TYPE to "double_bot")) ?: return false
    val top = blocks.find(SEAGRASS, mapOf(SEAGRASS_TYPE to "double_top")) ?: return false
    set(pos, bottom)
    set(pos.above, top)
    changed += pos
    changed += pos.above
    return true
}

/**
 * Sea pickles at [pos], [state], spreading over the living coral around
 * them, as bone meal does: water within two blocks of them, counting
 * across, at their height or a block below, with living coral under it,
 * takes from one to four pickles, one spot in six, and the pickles the
 * bone meal was used on grow to four.
 */
private fun FlatWorld.spreadPickles(pos: BlockPos, state: BlockState, random: Random, changed: MutableCollection<BlockPos>) {
    for (dx in -PICKLE_REACH..PICKLE_REACH) for (dz in -PICKLE_REACH..PICKLE_REACH) {
        if (abs(dx) + abs(dz) > PICKLE_REACH) continue
        for (y in pos.y - 1..pos.y) {
            val at = BlockPos(pos.x + dx, y, pos.z + dz)
            if (at == pos || random.nextInt(PICKLE_ONE_IN) != 0 || stateAt(at)?.liquid != Liquid.WATER) continue
            if (stateAt(at.below)?.name !in LIVING_CORAL_BLOCKS) continue
            blocks.find(SEA_PICKLE, mapOf(PICKLES to random.nextInt(MOST_PICKLES + 1), PICKLE_DEAD to bit(false)))?.let { set(at, it); changed += at }
        }
    }
    blocks.find(SEA_PICKLE, state.properties + (PICKLES to MOST_PICKLES))?.let { set(pos, it); changed += pos }
}

/**
 * Bone meal used on the top of the seabed at [ground]: dirt, sand,
 * gravel or clay under at least two blocks of water, the lower still, as
 * Bedrock has it. Seagrass grows on the seabed around it, as the game
 * plants it, from 128 tries, each wandering further from the water above
 * the block, and spoilt by any solid block on the way: now and then coral
 * or a coral fan instead, wherever the sea is, and seagrass already there
 * now and then grows tall. Null if it isn't seabed bone meal grows on, or
 * growth is switched off.
 */
fun FlatWorld.fertilizeSeabed(ground: BlockPos, random: Random = Random.Default): ItemUsedOn? {
    if (!features.growth || stateAt(ground)?.name !in SEABED) return null
    val water = ground.above
    if (!isWaterSource(water) || stateAt(water.above)?.liquid != Liquid.WATER) return null
    val changed = mutableListOf<BlockPos>()
    for (tried in 0 until SEABED_TRIES) {
        val at = wanderUnderwater(water, tried / 16, random) ?: continue
        val plant = if (random.nextInt(CORAL_ONE_IN) == 0) SEABED_CORALS.random(random) else SEAGRASS
        if (stateAt(at.below)?.hasFullFace(Face.UP) != true) continue
        val there = stateAt(at)
        when {
            isWaterSource(at) -> blocks.defaultOf(plant)?.let { set(at, it); changed += at }
            there?.name == SEAGRASS && there.properties[SEAGRASS_TYPE] == SEAGRASS_SHORT && isWaterSource(at.above) && random.nextInt(TALL_SEAGRASS_ONE_IN) == 0 ->
                growTallSeagrass(at, changed)
        }
    }
    return ItemUsedOn(changed, held = HeldAfter.USED_UP, events = listOf(WorldEvent(water, GROWTH_PARTICLES, 1, centred = false)))
}

/** Where [steps] random steps from [from] lead, each a block or none each way, up or down only now and then, or null if a solid block's in the way. */
private fun FlatWorld.wanderUnderwater(from: BlockPos, steps: Int, random: Random): BlockPos? {
    var at = from
    repeat(steps) {
        at = BlockPos(at.x + random.nextInt(3) - 1, at.y + (random.nextInt(3) - 1) * random.nextInt(3) / 2, at.z + random.nextInt(3) - 1)
        if (at.y !in MIN_Y..MAX_Y || stateAt(at)?.isFullCube == true) return null
    }
    return at
}
