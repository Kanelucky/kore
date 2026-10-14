package org.koremc.storage

import io.netty.buffer.ByteBufInputStream
import io.netty.buffer.ByteBufOutputStream
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType
import org.cloudburstmc.nbt.NbtUtils
import org.koremc.protocol.Difficulty
import org.koremc.protocol.GameMode
import org.koremc.registry.AIR_NETWORK_ID
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockTraits
import org.koremc.registry.ItemStacks
import org.koremc.world.BlockPos
import org.koremc.world.Chunk
import org.koremc.world.ChunkLoader
import org.koremc.world.ChunkPos
import org.koremc.world.FlatWorld
import org.koremc.world.GameRules
import org.koremc.world.LoadedChunk
import org.koremc.world.MAP_SIZE
import org.koremc.world.MIN_Y
import org.koremc.world.MapData
import org.koremc.world.PLAINS_BIOME_ID
import org.koremc.world.PalettedStorage
import org.koremc.world.SUB_CHUNK_COUNT
import org.koremc.world.restoreActor
import org.koremc.world.restoreBlockEntity
import org.koremc.world.restoreTicks
import org.koremc.world.savedActors
import org.koremc.world.savedBlockEntities
import org.koremc.world.savedTicks
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.random.Random

private val log = LoggerFactory.getLogger(WorldStorage::class.java)

/** The chunk format the game saves now, from 1.21.120. */
private const val CHUNK_VERSION: Byte = 42

/** How far along the game says a chunk it's finished making is. */
private const val FINISHED = 2

/**
 * The settings Kore runs a world with, besides what the world itself says.
 * A world made before keeps its own [gameMode], unless [forceGameMode], and
 * its own [seed]. [levelType] says what a new world is like.
 */
data class WorldSettings(
    val gameMode: GameMode,
    val difficulty: Difficulty,
    val cheats: Boolean,
    val seed: Long,
    val version: String,
    val protocol: Int,
    val forceGameMode: Boolean = false,
    val levelType: LevelType = LevelType.FLAT,
)

/** Where players' own data is kept between sessions: where they were, what they had, and so on, by their identity. */
interface PlayerSaves {
    fun load(player: UUID): NbtMap?

    fun save(player: UUID, data: NbtMap)

    companion object {
        /** Nowhere: players start afresh every time. */
        val NONE = object : PlayerSaves {
            override fun load(player: UUID): NbtMap? = null

            override fun save(player: UUID, data: NbtMap) {}
        }
    }
}

/**
 * A world as the game saves one, in its own folder: its settings in
 * level.dat, its name in levelname.txt, and in a LevelDB database, its
 * chunks, with their block entities, entities and the ticks their blocks
 * are due, so that redstone carries on where it was, its maps and its
 * players. A world Kore saves opens in the game, and a world the game
 * saves opens in Kore, as far as Kore has what's in it: chunks it hasn't
 * made yet are flat, as Kore has no other terrain, and entities and ticks
 * it doesn't have stay as they were.
 *
 * Chunks load as the world first looks at them, and saving writes only
 * what changed since: the blocks of chunks that changed, the block
 * entities, entities and ticks of chunks where those did, and the maps
 * drawn on.
 */
class WorldStorage private constructor(
    private val directory: Path,
    private val db: WorldDatabase,
    private var levelDat: LevelDat,
    private val blocks: BlockPalette,
    private val itemStacks: ItemStacks,
    /** The settings Kore runs the world with: those it was given, but for the game mode and seed a world made before keeps. */
    val settings: WorldSettings,
) : ChunkLoader, PlayerSaves, AutoCloseable {
    /** The world, which loads its chunks from here. */
    lateinit var world: FlatWorld
        private set

    /** The block entities, entities and ticks each chunk was last saved with, by a hash of how they were written, to write them again only once they change. */
    private val savedBlockEntities = HashMap<ChunkPos, Int>()
    private val savedActors = HashMap<ChunkPos, Int>()
    private val savedTicks = HashMap<ChunkPos, Int>()

    /** The ticks the game saved in each chunk for blocks Kore doesn't tick, with how long each had to go, written back as they were. */
    private val otherTicks = HashMap<ChunkPos, List<Pair<NbtMap, Long>>>()

    /** The unique IDs of the entities each chunk was last saved with, to remove those that went. */
    private val actorIds = HashMap<ChunkPos, List<Long>>()

    /** The entities the game saved in each chunk that Kore doesn't have, written back as they were. */
    private val otherActors = HashMap<ChunkPos, List<NbtMap>>()

    /**
     * What this run's entities are saved under, beside their numbers, which
     * start again from 1 each run, so that the unique IDs they're saved by
     * don't come round again.
     */
    private val run = Random.nextInt().toLong() shl 32

    /** The biome of chunks saved without any. */
    private var biome = PLAINS_BIOME_ID

    private var closed = false

    override fun load(pos: ChunkPos): LoadedChunk? {
        val blockEntityRecord = db[chunkKey(pos, BLOCK_ENTITIES)]
        val blockEntities = blockEntityRecord?.let(::readAll).orEmpty()
        val actors = actorsIn(pos)
        val ticks = db[chunkKey(pos, PENDING_TICKS)]
        // What the chunk has saved, so that a record goes once all that's in it does.
        blockEntityRecord?.let { savedBlockEntities[pos] = it.contentHashCode() }
        if (actors.isNotEmpty()) actorIds[pos] = actors.map { it.getLong("UniqueID", 0) }
        ticks?.let { savedTicks[pos] = it.contentHashCode() }
        val saved = db[chunkKey(pos, VERSION)] != null || db[chunkKey(pos, OLD_VERSION)] != null
        if (!saved && blockEntities.isEmpty() && actors.isEmpty() && ticks == null) return null
        return LoadedChunk(if (saved) blocksOf(pos) else null) {
            for (data in blockEntities) runCatching { restoreBlockEntity(data, itemStacks) }.onFailure { log.warn("Couldn't put back the block entity {}: {}", data, it.message) }
            otherActors[pos] = actors.filterNot { restoreActor(it, itemStacks) }
            ticks?.let { bytes ->
                runCatching { otherTicks[pos] = restoreTicks(readAll(bytes).first()) }.onFailure { log.warn("Couldn't put back the ticks of chunk {}, {}: {}", pos.x, pos.z, it.message) }
            }
        }
    }

    /** The blocks, the water inside them, and the biomes of the chunk at [pos] as they were saved. */
    private fun blocksOf(pos: ChunkPos): Chunk {
        val subChunks = MutableList(SUB_CHUNK_COUNT) { index ->
            db[chunkKey(pos, SUB_CHUNK, subChunkHeight(index))]?.let { bytes ->
                runCatching { decodeSubChunk(bytes, blocks) }.onFailure { log.warn("Couldn't read a sub-chunk of chunk {}, {}: {}", pos.x, pos.z, it.message) }.getOrNull()
            }
        }
        val top = subChunks.indexOfLast { it != null && it.blocks.palette != listOf(AIR_NETWORK_ID) }
        val storages = (0..top).map { subChunks[it]?.blocks ?: PalettedStorage.filled(AIR_NETWORK_ID) }
        val liquids = (0..top).map { subChunks[it]?.liquid }.dropLastWhile { it == null }
        val biomes = db[chunkKey(pos, DATA_3D)]?.let(::decodeBiomes) ?: List(SUB_CHUNK_COUNT) { PalettedStorage.filled(biome) }
        return Chunk(storages, biomes, liquids)
    }

    /** The entities the game saved in the chunk at [pos]: each with a record of its own, or, from older worlds, one after another. */
    private fun actorsIn(pos: ChunkPos): List<NbtMap> {
        val ids = db[entityListKey(pos)] ?: return db[chunkKey(pos, OLD_ENTITIES)]?.let(::readAll).orEmpty()
        val list = ByteBuffer.wrap(ids).order(ByteOrder.LITTLE_ENDIAN)
        return List(ids.size / 8) { list.getLong() }.mapNotNull { id -> db[entityKey(id)]?.let(::readAll)?.firstOrNull() }
    }

    /**
     * Saves what changed in the world since it was last saved: the blocks
     * of each chunk players changed, the block entities and entities of the
     * chunks where those changed, the maps drawn on since, and the world's
     * settings. Run on the world's thread.
     */
    fun save() {
        if (closed) return
        val world = world
        db.write {
            for (pos in world.unsaved) world.chunks[pos]?.let { saveBlocks(pos, it) }
            saveBlockEntities(world.savedBlockEntities())
            saveActors(world.savedActors { id -> run or id })
            saveTicks(world.savedTicks(), world.blockTicks)
            for (map in world.maps.all) if (map.unsaved) {
                set(mapKey(map.id), write(mapData(map)))
                map.unsaved = false
            }
        }
        world.unsaved.clear()
        levelDat = levelDat.updated(world.spawn, settings.gameMode, settings.difficulty, settings.cheats, settings.version, settings.protocol)
            .keeping(world.time, world.gameTime, world.weather, world.rules)
        levelDat.write(directory.resolve("level.dat"))
        directory.resolve("levelname.txt").writeText(levelDat.name)
    }

    /** Writes the blocks of [chunk], at [pos], as the game saves a chunk it's finished making. */
    private fun WorldDatabase.Changes.saveBlocks(pos: ChunkPos, chunk: Chunk) {
        set(chunkKey(pos, VERSION), byteArrayOf(CHUNK_VERSION))
        set(chunkKey(pos, DATA_3D), encodeBiomes(chunk.biomes))
        for (index in 0 until SUB_CHUNK_COUNT) {
            val key = chunkKey(pos, SUB_CHUNK, subChunkHeight(index))
            val blocks = chunk.subChunks.getOrNull(index)
            if (blocks == null) delete(key) else set(key, encodeSubChunk(subChunkHeight(index), blocks, this@WorldStorage.blocks, chunk.liquids.getOrNull(index)))
        }
        set(chunkKey(pos, FINALISED), ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(FINISHED).array())
        delete(chunkKey(pos, CHECKSUMS))
    }

    /** Writes the block entities of each chunk where they changed since they were last saved, [now] being all there are. */
    private fun WorldDatabase.Changes.saveBlockEntities(now: Map<ChunkPos, List<NbtMap>>) {
        for (pos in now.keys + savedBlockEntities.keys) {
            val written = now[pos]?.let { write(*it.toTypedArray()) }
            if (written?.contentHashCode() == savedBlockEntities[pos]) continue
            if (written == null) {
                delete(chunkKey(pos, BLOCK_ENTITIES))
                savedBlockEntities -= pos
            } else {
                set(chunkKey(pos, BLOCK_ENTITIES), written)
                savedBlockEntities[pos] = written.contentHashCode()
            }
        }
    }

    /**
     * Writes the entities of each chunk where they changed since they were
     * last saved, [now] being the items and orbs there are, with those the
     * game saved that Kore doesn't have: a record of each, and a list of
     * each chunk's, removing those that went.
     */
    private fun WorldDatabase.Changes.saveActors(now: Map<ChunkPos, List<NbtMap>>) {
        for (pos in now.keys + otherActors.keys + actorIds.keys) {
            val actors = now[pos].orEmpty() + otherActors[pos].orEmpty()
            val written = actors.map { write(it) }
            val hash = written.fold(0) { hash, bytes -> 31 * hash + bytes.contentHashCode() }
            if (actors.isEmpty() && pos !in actorIds) continue
            if (hash == savedActors[pos] && pos in actorIds) continue
            val ids = actors.map { it.getLong("UniqueID", 0) }
            actorIds[pos].orEmpty().filter { it !in ids }.forEach { delete(entityKey(it)) }
            actors.forEachIndexed { i, actor -> set(entityKey(ids[i]), written[i]) }
            if (ids.isEmpty()) {
                delete(entityListKey(pos))
                actorIds -= pos
                savedActors -= pos
            } else {
                set(entityListKey(pos), ByteBuffer.allocate(ids.size * 8).order(ByteOrder.LITTLE_ENDIAN).apply { ids.forEach(::putLong) }.array())
                actorIds[pos] = ids
                savedActors[pos] = hash
            }
            delete(chunkKey(pos, OLD_ENTITIES))
        }
    }

    /**
     * Writes the ticks the blocks of each chunk are due where they changed
     * since they were last saved, [now] being Kore's, on block tick [tick],
     * with those the game saved that Kore doesn't tick.
     */
    private fun WorldDatabase.Changes.saveTicks(now: Map<ChunkPos, List<NbtMap>>, tick: Long) {
        for (pos in now.keys + otherTicks.keys + savedTicks.keys) {
            val others = otherTicks[pos].orEmpty().map { (other, wait) -> other.toBuilder().putLong("time", tick + wait).build() }
            val ticks = now[pos].orEmpty() + others
            val written = ticks.takeIf { it.isNotEmpty() }?.let { write(NbtMap.builder().putInt("currentTick", tick.toInt()).putList("tickList", NbtType.COMPOUND, it).build()) }
            if (written?.contentHashCode() == savedTicks[pos]) continue
            if (written == null) {
                delete(chunkKey(pos, PENDING_TICKS))
                savedTicks -= pos
            } else {
                set(chunkKey(pos, PENDING_TICKS), written)
                savedTicks[pos] = written.contentHashCode()
            }
        }
    }

    override fun load(player: UUID): NbtMap? {
        val index = db[playerKey(player)]?.let(::readAll)?.firstOrNull()
        val key = index?.getString("ServerId", null)?.takeIf { it.isNotEmpty() } ?: "player_server_$player"
        return db[key.toByteArray()]?.let(::readAll)?.firstOrNull()
    }

    override fun save(player: UUID, data: NbtMap) {
        if (closed) return
        val key = "player_server_$player"
        val index = NbtMap.builder().putString("MsaId", player.toString()).putString("SelfSignedId", "").putString("ServerId", key).build()
        db.write {
            set(playerKey(player), write(index))
            set(key.toByteArray(), write(data))
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        db.close()
    }

    companion object {
        /**
         * Opens the world in [directory], or makes a new flat world called
         * [name] there, of [defaultLayers], with their platform on them, if
         * there's none. Its blocks and
         * items are [blocks] and [items], what the game says of the blocks,
         * like what liquid does to them, [traits], and Kore runs it with
         * [settings], but for the game mode and seed a world made before keeps.
         */
        fun open(
            directory: Path,
            name: String,
            defaultLayers: FlatLayers,
            blocks: BlockPalette,
            items: ItemStacks,
            settings: WorldSettings,
            traits: BlockTraits = BlockTraits.NONE,
        ): WorldStorage {
            Files.createDirectories(directory)
            val levelDatFile = directory.resolve("level.dat")
            val existing = levelDatFile.exists()
            val levelDat = if (existing) {
                LevelDat.read(levelDatFile)
            } else {
                if (settings.levelType != LevelType.FLAT) log.warn("Kore makes only flat worlds so far, so the new world \"{}\" is flat, not {}", name, settings.levelType)
                val spawn = BlockPos(0, MIN_Y + defaultLayers.layers.size, 0)
                LevelDat.create(name, defaultLayers, spawn, settings.gameMode, settings.difficulty, settings.seed, settings.cheats, settings.version, settings.protocol)
            }
            val layers = levelDat.flatLayers(blocks).takeIf { levelDat.generator == FLAT_GENERATOR } ?: defaultLayers.also {
                if (existing) log.warn("Kore makes only flat terrain, so the chunks of {} the game hasn't made yet will be flat", directory)
            }
            val kept = settings.copy(
                gameMode = levelDat.gameMode?.takeUnless { settings.forceGameMode } ?: settings.gameMode,
                seed = levelDat.seed,
            )
            if (kept.gameMode != settings.gameMode) {
                log.info("The world \"{}\" keeps its game mode, {}, as force-gamemode is off", levelDat.name, kept.gameMode.name.lowercase())
            }
            val storage = WorldStorage(directory, WorldDatabase.open(directory.resolve("db")), levelDat, blocks, items, kept)
            storage.biome = layers.biome
            val spawn = levelDat.spawn ?: levelDat.groundSpawn.let { (x, z) -> BlockPos(x, MIN_Y + layers.layers.size, z) }
            storage.world = FlatWorld(blocks, layers.layers, layers.biome, loader = storage, spawn = spawn, traits = traits).apply {
                time = levelDat.time
                gameTime = levelDat.gameTime
                rules = GameRules(levelDat.gameRules)
                weather = levelDat.weather()
            }
            for ((_, value) in storage.db.startingWith(MAP_PREFIX)) {
                runCatching { mapFrom(readAll(value).first()) }.getOrNull()?.let(storage.world.maps::add)
            }
            log.info("{} the world \"{}\" in {}", if (existing) "Opened" else "Made", levelDat.name, directory.toAbsolutePath())
            if (!existing) {
                for ((pos, state) in defaultLayers.platform) storage.world[pos] = state
                storage.save()
            }
            return storage
        }
    }
}

/** The key under which the game finds where a player's data is. */
private fun playerKey(player: UUID) = "player_$player".toByteArray()

/** [tags] as little-endian NBT, one after another. */
private fun write(vararg tags: NbtMap): ByteArray {
    val out = Unpooled.buffer()
    val writer = NbtUtils.createWriterLE(ByteBufOutputStream(out))
    tags.forEach(writer::writeTag)
    return ByteBufUtil.getBytes(out)
}

/** Every NBT compound in [bytes], one after another. */
private fun readAll(bytes: ByteArray): List<NbtMap> {
    val input = Unpooled.wrappedBuffer(bytes)
    val reader = NbtUtils.createReaderLE(ByteBufInputStream(input))
    val tags = mutableListOf<NbtMap>()
    while (input.isReadable) tags += reader.readTag() as NbtMap
    return tags
}

/** [map] as the game saves one, with each pixel's red, green, blue and alpha, and what Kore keeps besides. */
private fun mapData(map: MapData) = NbtMap.builder()
    .putLong("mapId", map.id)
    .putLong("parentMapId", map.parentId ?: -1)
    .putByte("dimension", 0)
    .putBoolean("fullyExplored", false)
    .putBoolean("mapLocked", map.locked)
    .putByte("scale", map.scale.toByte())
    .putShort("height", MAP_SIZE.toShort())
    .putShort("width", MAP_SIZE.toShort())
    .putInt("xCenter", map.centreX)
    .putInt("zCenter", map.centreZ)
    .putBoolean("unlimitedTracking", false)
    .putByteArray("colors", ByteArray(MAP_SIZE * MAP_SIZE * 4) { i -> channelOf(map.pixels[i / 4], i % 4) })
    .putList("decorations", NbtType.COMPOUND, emptyList())
    .putBoolean("map_display_players", map.showsPlayers)
    .putInt("map_name_index", map.nameIndex)
    .build()

/** One of the channels of the ARGB colour [argb], in the order the game saves a pixel's: red, green, blue, then alpha. */
private fun channelOf(argb: Int, channel: Int): Byte = when (channel) {
    0 -> argb shr 16
    1 -> argb shr 8
    2 -> argb
    else -> argb ushr 24
}.toByte()

/** A map the game saved as [data]. */
private fun mapFrom(data: NbtMap): MapData {
    val colours = data.getByteArray("colors", ByteArray(0))
    val pixels = IntArray(MAP_SIZE * MAP_SIZE) { i ->
        if (colours.size < (i + 1) * 4) return@IntArray 0
        fun channel(at: Int) = colours[i * 4 + at].toInt() and 0xFF
        (channel(3) shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
    }
    return MapData(
        id = data.getLong("mapId"),
        scale = data.getByte("scale", 0).toInt(),
        centreX = data.getInt("xCenter", 0),
        centreZ = data.getInt("zCenter", 0),
        showsPlayers = data.getBoolean("map_display_players", false),
        locked = data.getBoolean("mapLocked", false),
        pixels = pixels,
        nameIndex = data.getInt("map_name_index", 0),
        parentId = data.getLong("parentMapId", -1).takeIf { it != -1L },
    ).also { it.unsaved = false }
}
