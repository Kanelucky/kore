package org.koremc.storage

import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.DB
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import org.koremc.world.ChunkPos
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

/**
 * A world's LevelDB database, the `db` folder the game keeps a world's
 * chunks, entities, maps and players in. It writes as the game does, its
 * blocks compressed with raw deflate, and reads whatever the game wrote.
 */
class WorldDatabase private constructor(private val db: DB) : AutoCloseable {
    operator fun get(key: ByteArray): ByteArray? = db.get(key)

    /** Makes every change [changes] makes, all together, or none if it fails. */
    fun write(changes: Changes.() -> Unit) {
        db.createWriteBatch().use { batch ->
            Changes(batch).changes()
            db.write(batch)
        }
    }

    /** Every key starting with [prefix], with its value, in order. */
    fun startingWith(prefix: ByteArray): List<Pair<ByteArray, ByteArray>> {
        val found = mutableListOf<Pair<ByteArray, ByteArray>>()
        db.iterator().use { entries ->
            entries.seek(prefix)
            while (entries.hasNext()) {
                val (key, value) = entries.next().let { it.key to it.value }
                if (key.size < prefix.size || !prefix.indices.all { key[it] == prefix[it] }) break
                found += key to value
            }
        }
        return found
    }

    override fun close() = db.close()

    /** The changes one [write] makes. */
    class Changes(private val batch: org.iq80.leveldb.WriteBatch) {
        operator fun set(key: ByteArray, value: ByteArray) {
            batch.put(key, value)
        }

        fun delete(key: ByteArray) {
            batch.delete(key)
        }
    }

    companion object {
        /** Opens the database in [directory], making it if it isn't there. */
        fun open(directory: Path): WorldDatabase {
            val options = Options().createIfMissing(true).compressionType(CompressionType.ZLIB_RAW)
            return WorldDatabase(Iq80DBFactory.factory.open(directory.toFile(), options))
        }
    }
}

// The tags a chunk's records are keyed by, after its position, as the game names them.

/** The chunk's format version, a byte. */
internal const val VERSION: Byte = 0x2C

/** The version before [VERSION] took its place, which the game still reads. */
internal const val OLD_VERSION: Byte = 0x76

/** A height map, then the chunk's biomes, a storage for each sub-chunk. */
internal const val DATA_3D: Byte = 0x2B

/** One sub-chunk's blocks, keyed by its height in sub-chunks too. */
internal const val SUB_CHUNK: Byte = 0x2F

/** The chunk's block entities, as NBT one after another. */
internal const val BLOCK_ENTITIES: Byte = 0x31

/** The chunk's entities as the game kept them before each had a record of its own. */
internal const val OLD_ENTITIES: Byte = 0x32

/** How far the chunk got being made, as a little-endian int: 2 for done. */
internal const val FINALISED: Byte = 0x36

/** Checksums of the chunk's records, from older versions, which a change makes wrong. */
internal const val CHECKSUMS: Byte = 0x3B

/** The chunk's position as its keys start, in the overworld: its x then its z, each a little-endian int. */
internal fun chunkIndex(pos: ChunkPos): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(pos.x).putInt(pos.z).array()

/** The key of the record tagged [tag] of the chunk at [pos], for a sub-chunk with its height [subChunk]. */
internal fun chunkKey(pos: ChunkPos, tag: Byte, subChunk: Int? = null): ByteArray =
    chunkIndex(pos) + tag + listOfNotNull(subChunk?.toByte())

/** The key listing the entities in the chunk at [pos], by unique ID, each a little-endian long. */
internal fun entityListKey(pos: ChunkPos): ByteArray = "digp".toByteArray() + chunkIndex(pos)

/** The key of the entity with the unique ID [id]. */
internal fun entityKey(id: Long): ByteArray = "actorprefix".toByteArray() + ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(id).array()

/** The key of the map numbered [id]. */
internal fun mapKey(id: Long): ByteArray = "map_$id".toByteArray()

/** What every map's key starts with. */
internal val MAP_PREFIX = "map_".toByteArray()
