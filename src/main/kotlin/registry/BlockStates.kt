package org.koremc.registry

import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtUtils
import java.io.ByteArrayOutputStream

/** The block that fills any space nothing else does. It has no properties. */
const val AIR = "minecraft:air"

/** Air's network ID, which every world has more of than anything else. */
val AIR_NETWORK_ID = blockStateHash(AIR, emptyMap())

/** The block that stands in for any the client doesn't know. Its network ID is fixed rather than hashed. */
const val UNKNOWN_BLOCK = "minecraft:unknown"
private const val UNKNOWN_BLOCK_NETWORK_ID = -2

private const val FNV1A_32_OFFSET_BASIS = 0x811C9DC5.toInt()
private const val FNV1A_32_PRIME = 0x01000193

/**
 * One permutation of a block: its name and a value for each of its
 * properties. Properties differ from block to block and come from the game's
 * data, so they stay NBT rather than typed fields. Values are bytes, ints or
 * strings, and the type is part of the state: a byte 1 and an int 1 make
 * different states.
 */
data class BlockState(
    val name: String,
    val properties: NbtMap,
    /** The block format version, one byte each of major, minor, patch and revision: 18168865 is 1.21.60.33. */
    val version: Int,
) {
    /** What the client calls this state on the network, since Kore tells it block network IDs are hashes. */
    val networkId = blockStateHash(name, properties)
}

/**
 * The network ID the client gives a block state when block network IDs are
 * hashes: the 32-bit FNV-1a hash of the state written as little-endian NBT,
 * `{"name": name, "states": {properties, sorted by key}}`. The one exception
 * is [UNKNOWN_BLOCK], which is always -2.
 *
 * Property types count, so give literal maps their type: in
 * `mapOf("candles" to 3, "lit" to 0.toByte())`, Kotlin makes the 3 a byte as
 * well, which hashes as a different state. `mapOf<String, Any>(...)` keeps it
 * an int.
 *
 * Mojang's protocol docs name only the `StartGame` flag, not the rule. The
 * tests check the rule against the network ID of every state in the game's
 * own palette.
 */
fun blockStateHash(name: String, properties: Map<String, Any>): Int {
    if (name == UNKNOWN_BLOCK) return UNKNOWN_BLOCK_NETWORK_ID

    val state = NbtMap.builder()
        .putString("name", name)
        .putCompound("states", NbtMap.fromMap(properties.toSortedMap()))
        .build()
    val bytes = ByteArrayOutputStream().also { NbtUtils.createWriterLE(it).writeTag(state) }.toByteArray()
    return bytes.fold(FNV1A_32_OFFSET_BASIS) { hash, byte -> (hash xor (byte.toInt() and 0xFF)) * FNV1A_32_PRIME }
}

/**
 * A vanilla block defined in data rather than in the game's code, like the
 * wool and concrete stairs and slabs added in 26.50. The client only knows
 * how such a block looks and behaves once the server sends it [data]: its
 * components, tags and creative category.
 */
data class BlockDefinition(val name: String, val data: NbtMap)

/** Every block state the client knows, found by the network ID the client sends when it names one. */
class BlockPalette(val states: List<BlockState>) {
    private val byNetworkId = states.associateBy(BlockState::networkId)

    /** Each block's first state, which in the game's palette is its default. */
    private val defaults = states.reversed().associateBy(BlockState::name)

    init {
        require(byNetworkId.size == states.size) {
            "Block states share network IDs: " + states.groupBy(BlockState::networkId).values.filter { it.size > 1 }.take(3)
        }
    }

    /** The state the client means by [networkId], or null if Kore doesn't know it. */
    operator fun get(networkId: Int): BlockState? = byNetworkId[networkId]

    /** The state of [name] with [properties], or null if the client knows no such state. Mind the properties' types, as [blockStateHash] explains. */
    fun find(name: String, properties: Map<String, Any> = emptyMap()): BlockState? =
        get(blockStateHash(name, properties))?.takeIf { it.name == name }

    /** The default state of the block called [name], or null if the client knows no such block. */
    fun defaultOf(name: String): BlockState? = defaults[name]

    /** The state of [name] with [properties], which must be one the client knows. */
    fun stateOf(name: String, properties: Map<String, Any> = emptyMap()): BlockState =
        find(name, properties) ?: throw IllegalArgumentException("No block state $name $properties")
}
