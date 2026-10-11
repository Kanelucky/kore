package org.koremc

import org.koremc.network.DisconnectReason
import org.koremc.network.NetherNetConnection
import org.koremc.protocol.ActorDataId
import org.koremc.protocol.ActorFlag
import org.koremc.protocol.Attribute
import org.koremc.protocol.BatchCodec
import org.koremc.protocol.BiomeDefinitionList
import org.koremc.protocol.ClientCacheStatus
import org.koremc.protocol.ChunkRadiusUpdated
import org.koremc.protocol.ClientPacket
import org.koremc.protocol.CreativeContent
import org.koremc.protocol.Disconnect
import org.koremc.protocol.FloatItem
import org.koremc.protocol.ItemRegistry
import org.koremc.protocol.JigsawStructureData
import org.koremc.protocol.LevelChunk
import org.koremc.protocol.Login
import org.koremc.protocol.LoginRequest
import org.koremc.protocol.NetworkChunkPublisherUpdate
import org.koremc.protocol.NetworkSettings
import org.koremc.protocol.PacketException
import org.koremc.protocol.PermissionLevel
import org.koremc.protocol.PlayStatus
import org.koremc.protocol.PlayerAuthInput
import org.koremc.protocol.RequestChunkRadius
import org.koremc.protocol.RequestNetworkSettings
import org.koremc.protocol.ResourcePackClientResponse
import org.koremc.protocol.ResourcePackStack
import org.koremc.protocol.ResourcePacksInfo
import org.koremc.protocol.ServerPacket
import org.koremc.protocol.SetActorData
import org.koremc.protocol.SetLocalPlayerAsInitialised
import org.koremc.protocol.ShortItem
import org.koremc.protocol.StartGame
import org.koremc.protocol.UnhandledPacket
import org.koremc.protocol.UpdateAbilities
import org.koremc.protocol.UpdateAttributes
import org.koremc.protocol.abilitiesFor
import org.koremc.protocol.VoxelShapes
import org.koremc.protocol.decodeClientPacket
import org.koremc.protocol.encode
import org.koremc.protocol.flagsItem
import org.koremc.protocol.parseConnectionRequest
import org.koremc.registry.VanillaData
import org.koremc.world.ChunkPos
import org.koremc.world.FlatWorld
import org.koremc.world.PLAYER_EYE_HEIGHT
import org.koremc.world.PLAYER_HEIGHT
import org.koremc.world.PLAYER_MAX_AIR
import org.koremc.world.PLAYER_WALK_SPEED
import org.koremc.world.PLAYER_WIDTH
import org.koremc.world.Vec3
import org.koremc.world.chunksAround
import org.slf4j.LoggerFactory
import java.security.interfaces.ECPublicKey
import kotlin.math.abs

private val log = LoggerFactory.getLogger(Session::class.java)

/** Every batch in and out, by packet. The console leaves it out, and each run's log file has it all. */
private val packetLog = LoggerFactory.getLogger("org.koremc.packets")

/** Batches this size or larger are compressed. Small ones cost more to compress than they save. */
private const val COMPRESSION_THRESHOLD = 256

/** The smallest view distance Kore settles on, whatever the client asks for. */
private const val MIN_CHUNK_RADIUS = 2

/** Chunks per batch, so the client can start building terrain before the last of them arrives. */
private const val CHUNKS_PER_BATCH = 16

/** No real world reaches this far out, and it keeps block and chunk coordinates well inside an Int. */
private const val MAX_COORDINATE = 30_000_000f

/**
 * How many chunks a player can stray from the centre of their view before
 * it follows them. Players often step back and forth over a chunk border;
 * if the view followed every step, each one would resend the same row of
 * chunks. Strays of less than this send nothing.
 */
private const val RECENTRE_DISTANCE = 2

/**
 * One player's protocol session. It owns the batch codec, so it decides
 * when compression switches on, and walks the login sequence one packet
 * at a time:
 *
 * 1. `RequestNetworkSettings` names the client's protocol. A mismatch gets a
 *    failed `PlayStatus`; a match gets `NetworkSettings`, after which every
 *    batch is compressed.
 * 2. `Login` carries the client's identity and client data, which must
 *    authenticate. A login that doesn't gets a `Disconnect`. One that does
 *    gets `PlayStatus(LOGIN_SUCCESS)` straight away: NetherNet has no
 *    encryption handshake, since DTLS already covers it.
 * 3. Resource packs. Kore offers none, the client answers that it has them
 *    all, Kore sends the (vanilla) pack stack, and the client confirms it
 *    has applied it.
 * 4. `StartGame` sets up the world. The jigsaw structure data and voxel
 *    shapes go just before it, as the client insists, and the game's
 *    definitions just after:
 *    the `ItemRegistry`, with every item and the runtime ID it travels by,
 *    then the biomes and the (empty) creative inventory.
 * 5. The client asks how far it can see. Kore settles on a chunk radius,
 *    sends every chunk within it, nearest first, then
 *    `PlayStatus(PLAYER_SPAWN)`.
 * 6. Once its player is in the world, the client sends
 *    `SetLocalPlayerAsInitialised`, and the player is in the game. Kore
 *    tells the client about its player: that it breathes and falls, its
 *    attributes, and what its game mode lets it do.
 *
 * From then on, `PlayerAuthInput` reports where the client predicts its
 * player is, every tick. Kore takes its word for it, as Mojang's movement
 * guide allows a server without physics to, and moves the player's view
 * with them once they stray from its centre, sending the chunks that come
 * into it.
 *
 * Runs on the UDP event loop, with the connection callbacks that drive it.
 */
class Session(
    private val config: ServerConfig,
    /** What follows `StartGame`: the item registry first, then the rest of the game's definitions. Shared by every session. */
    private val definitions: List<ServerPacket>,
    private val world: FlatWorld,
    private val networkId: String,
    /** The player's entity ID, unique across the server. */
    private val entityId: Long,
    /** The key the client proved when it connected, which its login must match. */
    private val offerKey: ECPublicKey?,
    private val authenticator: LoginAuthenticator,
    private val send: (ByteArray) -> Unit,
) {
    private enum class State { AWAITING_NETWORK_SETTINGS_REQUEST, AWAITING_LOGIN, NEGOTIATING_RESOURCE_PACKS, LOADING_WORLD, SPAWNING, IN_GAME, CLOSED }

    private val batches = BatchCodec()
    private var state = State.AWAITING_NETWORK_SETTINGS_REQUEST

    /** The player, once their login has authenticated. */
    var player: PlayerIdentity? = null
        private set

    /** The authenticated login, kept for its client data: settings, skin and so on, which only the player's key vouches for. */
    var login: LoginRequest? = null
        private set

    /** Whether the client can reuse chunk data it has cached, as it said once logged in. */
    var cacheSupported = false
        private set

    /** Whether `StartGame` has gone out and the client is loading the world. */
    val isLoadingWorld get() = state == State.LOADING_WORLD

    /** Whether the player has spawned. */
    val isInGame get() = state == State.IN_GAME

    /** How many chunks around the player to send, once the client has asked. */
    var chunkRadius: Int? = null
        private set

    /** The chunks the client has, as far as Kore knows. */
    private val sentChunks = HashSet<ChunkPos>()

    /** Where the player's eyes are, as the client last reported. */
    var position = Vec3(world.spawn.x + 0.5f, world.spawn.y + PLAYER_EYE_HEIGHT, world.spawn.z + 0.5f)
        private set

    /** The chunk the player's view is centred on. */
    private var viewCentre = world.spawn.chunk

    fun receive(message: ByteArray) {
        val packets = try {
            batches.decode(message).map(::decodeClientPacket)
        } catch (e: PacketException) {
            log.warn("Dropped a batch from network {}: {}", networkId, e.message)
            return
        }
        // Movement arrives every tick in game, along with packets Kore ignores, so those batches only go to trace.
        if (state == State.IN_GAME && packets.all { it is PlayerAuthInput || it is UnhandledPacket }) {
            packetLog.trace("<- {}: {} ({} bytes)", who, packets.joinToString(), message.size)
        } else {
            packetLog.debug("<- {}: {} ({} bytes)", who, packets.joinToString(), message.size)
        }
        packets.forEach(::handle)
    }

    private fun handle(packet: ClientPacket) {
        when (state) {
            State.AWAITING_NETWORK_SETTINGS_REQUEST -> if (packet is RequestNetworkSettings) onRequestNetworkSettings(packet) else unexpected(packet)
            State.AWAITING_LOGIN -> if (packet is Login) onLogin(packet) else unexpected(packet)
            State.NEGOTIATING_RESOURCE_PACKS -> when (packet) {
                is ClientCacheStatus -> cacheSupported = packet.supported
                is ResourcePackClientResponse -> onResourcePackResponse(packet)
                else -> unexpected(packet)
            }
            State.LOADING_WORLD -> if (packet is RequestChunkRadius) onRequestChunkRadius(packet) else unexpected(packet)
            State.SPAWNING, State.IN_GAME -> when (packet) {
                is PlayerAuthInput -> onMove(packet)
                is RequestChunkRadius -> onRequestChunkRadius(packet)
                is SetLocalPlayerAsInitialised -> if (state == State.SPAWNING) onPlayerInitialised(packet) else unexpected(packet)
                // Movement and the rest of gameplay arrive many times a second, and Kore has nothing for them yet.
                else -> log.trace("Ignored {} from network {}", packet, networkId)
            }
            State.CLOSED -> unexpected(packet)
        }
    }

    private fun onRequestNetworkSettings(packet: RequestNetworkSettings) {
        if (!acceptsProtocol(packet.protocol)) return

        // NetworkSettings itself goes out uncompressed. Compression starts with the next batch, both ways.
        send(NetworkSettings(compressionThreshold = COMPRESSION_THRESHOLD))
        batches.enableCompression(COMPRESSION_THRESHOLD)
        state = State.AWAITING_LOGIN
    }

    private fun onLogin(packet: Login) {
        if (!acceptsProtocol(packet.protocol)) return

        val request = try {
            parseConnectionRequest(packet.connectionRequest)
        } catch (e: PacketException) {
            log.warn("Network {} sent a login Kore can't read: {}", networkId, e.message)
            reject()
            return
        }

        val player = try {
            authenticator.authenticate(request, offerKey)
        } catch (e: AuthenticationException) {
            // The reason stays in the log. The player only learns that they couldn't be verified.
            log.info("Rejected the login from network {}, claiming to be {}: {}", networkId, request.identity.displayName, e.message)
            reject()
            return
        }
        this.player = player
        login = request

        val client = request.clientInfo
        log.info(
            "{} ({}, {}) logged in on network {}{}: {} {}, OS {}, {}",
            player.displayName, player.xuid ?: "no XUID", player.uuid, networkId,
            if (player.verified) "" else " without verification (offline mode)",
            request.authenticationType ?: "unknown sign-in", client.gameVersion, client.deviceOs, client.languageCode,
        )

        // The pack list follows login success in the same batch, as the client asks for it next anyway.
        send(PlayStatus(PlayStatus.Status.LOGIN_SUCCESS), ResourcePacksInfo())
        state = State.NEGOTIATING_RESOURCE_PACKS
    }

    private fun onResourcePackResponse(packet: ResourcePackClientResponse) {
        when (packet.response) {
            ResourcePackClientResponse.Response.DOWNLOADING_FINISHED -> send(ResourcePackStack())

            // Kore offered nothing, so there's nothing to download. Carry on as though the client had everything.
            ResourcePackClientResponse.Response.DOWNLOADING -> {
                log.debug("Network {} asked for packs Kore never offered: {}", networkId, packet.packs)
                send(ResourcePackStack())
            }

            ResourcePackClientResponse.Response.RESOURCE_PACK_STACK_FINISHED -> startGame()

            ResourcePackClientResponse.Response.CANCEL -> {
                log.info("{} cancelled joining during resource packs", player?.displayName)
                state = State.CLOSED
            }
        }
    }

    private fun startGame() {
        val spawn = world.spawn
        send(
            JigsawStructureData,
            VoxelShapes,
            StartGame(
                entityId = entityId,
                runtimeId = entityId,
                playerGameMode = config.gameMode,
                position = position,
                seed = config.seed,
                worldGameMode = config.gameMode,
                difficulty = config.difficulty,
                worldSpawn = spawn,
                worldName = config.level,
                serverVersion = config.version,
            ),
            *definitions.toTypedArray(),
        )
        state = State.LOADING_WORLD
        log.info("{} is loading the world, spawning at {}", player?.displayName, spawn)
    }

    /**
     * Settles the view distance: what the client wants, within what it can
     * handle and what Kore allows. Then sends the chunks in view that the
     * client doesn't have, and the first time, lets the player spawn.
     */
    private fun onRequestChunkRadius(packet: RequestChunkRadius) {
        val wanted = if (packet.maxRadius > 0) minOf(packet.radius, packet.maxRadius) else packet.radius
        val radius = wanted.coerceIn(MIN_CHUNK_RADIUS, config.viewDistance)
        chunkRadius = radius
        val sent = updateView(radius, ChunkRadiusUpdated(radius))
        log.debug("{} asked for {} chunks and gets {}, {} of them new", player?.displayName, packet.radius, radius, sent)

        if (state == State.LOADING_WORLD) {
            send(PlayStatus(PlayStatus.Status.PLAYER_SPAWN))
            state = State.SPAWNING
        }
    }

    /**
     * Takes the client's word for where its player is. Straying far enough
     * from the centre of their view moves it onto them, which sends the
     * chunks coming into it.
     */
    private fun onMove(packet: PlayerAuthInput) {
        val reported = packet.position
        if (!reported.isPlausible()) {
            log.debug("{} reported an impossible position, {}", player?.displayName, reported)
            return
        }
        position = reported

        val chunk = reported.toBlockPos().chunk
        val radius = chunkRadius ?: return
        val dx = chunk.x - viewCentre.x
        val dz = chunk.z - viewCentre.z
        if (dx * dx + dz * dz < RECENTRE_DISTANCE * RECENTRE_DISTANCE) return

        viewCentre = chunk
        val sent = updateView(radius)
        log.debug("{}'s view moved to chunk {}, {}, so {} chunks came into it", player?.displayName, chunk.x, chunk.z, sent)
    }

    /**
     * Centres the client's view on [viewCentre], after any packets in
     * [first], then sends the chunks in view that the client doesn't have,
     * nearest first. Returns how many.
     */
    private fun updateView(radius: Int, vararg first: ServerPacket): Int {
        send(*first, NetworkChunkPublisherUpdate(position.toBlockPos(), radius * 16))

        val inView = chunksAround(viewCentre, radius)
        // The client drops chunks that fall out of view, so those need sending again if they come back.
        sentChunks.retainAll(inView.toSet())
        val unsent = inView.filter(sentChunks::add)
        unsent.chunked(CHUNKS_PER_BATCH).forEach { batch -> send(*batch.map { LevelChunk(it, world.chunkAt(it)) }.toTypedArray()) }
        return unsent.size
    }

    private fun Vec3.isPlausible() =
        listOf(x, y, z).all { it.isFinite() } && abs(x) <= MAX_COORDINATE && abs(z) <= MAX_COORDINATE

    private fun onPlayerInitialised(packet: SetLocalPlayerAsInitialised) {
        if (packet.runtimeId != entityId) {
            log.debug("Network {} initialised runtime ID {}, but its player's is {}", networkId, packet.runtimeId, entityId)
            return
        }
        state = State.IN_GAME
        send(*playerState())
        log.info("{} spawned", player?.displayName)
    }

    /**
     * What the client needs to know about its own player to move it like
     * the game does. Without the flags, the client treats its player as
     * something that doesn't breathe or fall. The attributes give it full
     * health and hunger (sprinting needs food) and its walking speed.
     */
    private fun playerState(): Array<ServerPacket> = arrayOf(
        SetActorData(
            entityId,
            listOf(
                flagsItem(setOf(ActorFlag.BREATHING, ActorFlag.HAS_GRAVITY, ActorFlag.HAS_COLLISION, ActorFlag.CAN_CLIMB)),
                ShortItem(ActorDataId.AIR_SUPPLY, PLAYER_MAX_AIR),
                ShortItem(ActorDataId.AIR_SUPPLY_MAX, PLAYER_MAX_AIR),
                FloatItem(ActorDataId.SCALE, 1f),
                FloatItem(ActorDataId.WIDTH, PLAYER_WIDTH),
                FloatItem(ActorDataId.HEIGHT, PLAYER_HEIGHT),
            ),
        ),
        UpdateAttributes(
            entityId,
            listOf(
                Attribute("minecraft:health", 0f, 20f, 20f),
                Attribute("minecraft:absorption", 0f, Float.MAX_VALUE, 0f),
                Attribute("minecraft:movement", 0f, Float.MAX_VALUE, PLAYER_WALK_SPEED),
                Attribute("minecraft:player.hunger", 0f, 20f, 20f),
                Attribute("minecraft:player.saturation", 0f, 20f, 5f, default = 20f), // The 5 a new player starts with
                Attribute("minecraft:player.exhaustion", 0f, 5f, 0f),
                Attribute("minecraft:player.level", 0f, Int.MAX_VALUE.toFloat(), 0f),
                Attribute("minecraft:player.experience", 0f, 1f, 0f),
            ),
        ),
        UpdateAbilities(entityId, PermissionLevel.MEMBER, abilitiesFor(config.gameMode), walkSpeed = PLAYER_WALK_SPEED),
    )

    private fun reject() {
        send(Disconnect(Disconnect.Reason.NOT_AUTHENTICATED))
        state = State.CLOSED
    }

    /** Turns away clients on another protocol, telling them whose version is behind. */
    private fun acceptsProtocol(clientProtocol: Int): Boolean {
        if (clientProtocol == config.protocol) return true

        log.info("Network {} speaks protocol {}, but Kore speaks {}", networkId, clientProtocol, config.protocol)
        val status = if (clientProtocol < config.protocol) PlayStatus.Status.LOGIN_FAILED_CLIENT_OLD else PlayStatus.Status.LOGIN_FAILED_SERVER_OLD
        send(PlayStatus(status))
        state = State.CLOSED
        return false
    }

    private fun unexpected(packet: ClientPacket) {
        log.debug("Ignored {} from network {} while {}", packet, networkId, state)
    }

    /** Sends [packets] together in one batch. */
    private fun send(vararg packets: ServerPacket) {
        val batch = batches.encode(packets.map { it.encode() })
        packetLog.debug("-> {}: {} ({} bytes)", who, names(packets), batch.size)
        send(batch)
    }

    /** Who the logs call this session: the player once they've logged in, their network until then. */
    private val who get() = player?.displayName ?: "network $networkId"
}

/** Packet names, with a run of the same packet counted rather than repeated: `LevelChunk x16`. */
private fun names(packets: Array<out ServerPacket>): String {
    val runs = mutableListOf<Pair<String, Int>>()
    for (packet in packets) {
        val name = packet::class.simpleName.orEmpty()
        val last = runs.lastOrNull()
        if (last != null && last.first == name) runs[runs.lastIndex] = name to last.second + 1 else runs += name to 1
    }
    return runs.joinToString { (name, count) -> if (count == 1) name else "$name x$count" }
}

/** Every connected player's session, driven by the transport's connection callbacks on the UDP event loop. */
class Sessions(private val config: ServerConfig, vanilla: VanillaData, private val world: FlatWorld, private val authenticator: LoginAuthenticator) {
    private val sessions = HashMap<NetherNetConnection, Session>()
    private var nextEntityId = 1L

    /** Built here, on start, so no player's login pays to encode them. */
    private val definitions = listOf(ItemRegistry(vanilla.items), BiomeDefinitionList(vanilla.biomes), CreativeContent)

    fun connect(connection: NetherNetConnection) {
        sessions[connection] = Session(config, definitions, world, connection.networkId, nextEntityId++, connection.clientKey, authenticator) { message ->
            connection.send(message)
        }
    }

    fun receive(connection: NetherNetConnection, message: ByteArray) {
        sessions[connection]?.receive(message)
    }

    fun disconnect(connection: NetherNetConnection, reason: DisconnectReason) {
        sessions.remove(connection)
        log.debug("Session for network {} ended: {}", connection.networkId, reason)
    }
}
