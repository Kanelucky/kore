package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.registry.BlockTraits
import org.koremc.registry.ItemStack
import kotlin.math.floor

internal const val BEACON = "minecraft:beacon"
private const val BEDROCK = "minecraft:bedrock"

/** The blocks a beacon's pyramid is built of, in any mix. */
private val PYRAMID_BLOCKS = setOf("iron_block", "gold_block", "emerald_block", "diamond_block", "netherite_block").mapTo(HashSet()) { "minecraft:$it" }

/** What players pay a beacon with for its powers: any one of these. */
private val PAYMENTS = setOf("iron_ingot", "gold_ingot", "emerald", "diamond", "netherite_ingot").mapTo(HashSet()) { "minecraft:$it" }

/** The most layers a beacon's pyramid has. */
const val MAX_PYRAMID = 4

/** How often beacons look at their pyramids, and give their powers to the players near them, in ticks. */
const val BEACON_INTERVAL = 80

/** The light a block has to stop all of to block a beacon's beam. */
private const val ALL_LIGHT = 15

/** The primary powers a beacon gives on each layer of pyramid it has, beside those of the layers under it. */
private val PRIMARY_POWERS = listOf(setOf(Effect.SPEED, Effect.HASTE), setOf(Effect.RESISTANCE, Effect.JUMP_BOOST), setOf(Effect.STRENGTH))

/** Whether [stack] pays a beacon for its powers. */
val ItemStack.paysBeacon: Boolean get() = item.name in PAYMENTS

/**
 * Whether a beacon on a pyramid of [level] layers gives [primary], and
 * [secondary] with it, or 0 for none. Each layer brings its primary powers,
 * and the fourth a secondary: Regeneration, or the primary's level II.
 */
fun beaconGives(level: Int, primary: Int, secondary: Int): Boolean {
    if (PRIMARY_POWERS.take(level).none { primary in it }) return false
    return secondary == 0 || (level >= MAX_PYRAMID && (secondary == Effect.REGENERATION || secondary == primary))
}

/**
 * How many layers of pyramid the beacon at [pos] stands on, up to four:
 * each a square of iron, gold, emerald, diamond or netherite blocks, a
 * block wider each way than the one above, the first three by three.
 */
fun FlatWorld.beaconLevel(pos: BlockPos): Int {
    for (layer in 1..MAX_PYRAMID) {
        for (x in -layer..layer) for (z in -layer..layer) {
            if (stateAt(BlockPos(pos.x + x, pos.y - layer, pos.z + z))?.name !in PYRAMID_BLOCKS) return layer - 1
        }
    }
    return MAX_PYRAMID
}

/** Whether a block over the beacon at [pos] blocks its beam: one that stops all light, other than bedrock. */
fun FlatWorld.beamBlocked(pos: BlockPos, traits: BlockTraits): Boolean {
    val top = MIN_Y + chunkAt(pos.chunk).subChunks.size * 16 - 1
    return (pos.y + 1..top).any { y ->
        val above = BlockPos(pos.x, y, pos.z)
        traits[blockAt(above)].lightDampening >= ALL_LIGHT && stateAt(above)?.name != BEDROCK
    }
}

/** The powers players chose for the beacon at [pos]: its primary effect and its secondary, each 0 for none. */
fun FlatWorld.beaconPowers(pos: BlockPos): Pair<Int, Int> {
    val data = blockEntityAt(pos) ?: return 0 to 0
    return data.getInt("primary", 0) to data.getInt("secondary", 0)
}

/** Gives the beacon at [pos] the powers [primary] and [secondary], which it keeps in its data for everyone with the chunk to see. */
fun FlatWorld.setBeaconPowers(pos: BlockPos, primary: Int, secondary: Int) {
    setBlockEntity(pos, NbtMap.builder().putString("id", "Beacon").putInt("primary", primary).putInt("secondary", secondary).build())
}

/**
 * A beacon whose beam shines, giving its powers: [effects] to the players
 * it [reaches], for [ticks].
 */
class Shining(val pos: BlockPos, val level: Int, val effects: List<EffectLevel>) {
    /** How long its powers last on a player, ten seconds on a pyramid of one layer, and two more for each layer on. */
    val ticks get() = (8 + 2 * level) * 20

    /**
     * Whether its powers reach a player whose feet are at [feet]: within
     * ten blocks across for each layer of pyramid, and ten more, one more
     * to the north and west, from as far below the beacon up to the top
     * of the world.
     */
    fun reaches(feet: Vec3): Boolean {
        val range = 10 + 10 * level
        val x = floor(feet.x).toInt() - pos.x
        val z = floor(feet.z).toInt() - pos.z
        return x in -range - 1..range && z in -range - 1..range && floor(feet.y).toInt() >= pos.y - range
    }
}

/** What a look at every beacon came to: the sounds of those whose beams came on or went out, and those shining. */
class BeaconsLooked(val sounds: List<BlockSound>, val shining: List<Shining>)

/**
 * Looks at every beacon, as the game does every [BEACON_INTERVAL] ticks.
 * A beacon's beam shines while it stands on a pyramid with nothing above
 * blocking it, and comes on or goes out with a sound. One that shines with
 * a power chosen gives it, as far as its pyramid allows: a secondary only
 * on four layers, the primary's level II if that's what was chosen.
 */
fun FlatWorld.lookAtBeacons(traits: BlockTraits): BeaconsLooked {
    val sounds = mutableListOf<BlockSound>()
    val shining = mutableListOf<Shining>()
    for ((pos, was) in beacons.toList()) {
        val level = beaconLevel(pos).takeIf { it > 0 && !beamBlocked(pos, traits) } ?: 0
        beacons[pos] = level
        if ((was > 0) != (level > 0)) sounds += BlockSound(pos, if (level > 0) "beacon.activate" else "beacon.deactivate", -1)
        if (level == 0) continue
        val (primary, secondary) = beaconPowers(pos)
        val given = if (level < MAX_PYRAMID) 0 else secondary
        if (primary == 0 || !beaconGives(level, primary, given)) continue
        val effects = when (given) {
            0 -> listOf(EffectLevel(primary))
            primary -> listOf(EffectLevel(primary, amplifier = 1))
            else -> listOf(EffectLevel(primary), EffectLevel(given))
        }
        shining += Shining(pos, level, effects)
    }
    return BeaconsLooked(sounds, shining)
}
