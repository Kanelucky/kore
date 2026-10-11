package org.koremc

import org.koremc.network.DisconnectReason
import org.koremc.network.NetherNetConnection
import org.koremc.protocol.BatchCodec
import org.koremc.protocol.ClientCacheStatus
import org.koremc.protocol.ChunkRadiusUpdated
import org.koremc.protocol.ClientPacket
import org.koremc.protocol.Disconnect
import org.koremc.protocol.ItemRegistry
import org.koremc.protocol.Login
import org.koremc.protocol.LoginRequest
import org.koremc.protocol.NetworkSettings
import org.koremc.protocol.PacketException
import org.koremc.protocol.PlayStatus
import org.koremc.protocol.RequestChunkRadius
import org.koremc.protocol.RequestNetworkSettings
import org.koremc.protocol.ResourcePackClientResponse
import org.koremc.protocol.ResourcePackStack
import org.koremc.protocol.ResourcePacksInfo
import org.koremc.protocol.ServerPacket
import org.koremc.protocol.StartGame
import org.koremc.protocol.Vec3
import org.koremc.protocol.decodeClientPacket
import org.koremc.protocol.encode
import org.koremc.protocol.parseConnectionRequest
import org.koremc.registry.VanillaData
import org.slf4j.LoggerFactory
import java.security.interfaces.ECPublicKey

private val log = LoggerFactory.getLogger(Session::class.java)

/** Batches this size or larger are compressed. Small ones cost more to compress than they save. */
private const val COMPRESSION_THRESHOLD = 256

/** How far above their feet a player's eyes are, which is where Bedrock places them. */
private const val EYE_HEIGHT = 1.62f

/** The smallest view distance Kore settles on, whatever the client asks for. */
private const val MIN_CHUNK_RADIUS = 2

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
 * 4. `StartGame` sets up the world, followed by the `ItemRegistry`: every
 *    vanilla item and the runtime ID it travels by. The client then asks how
 *    far it can see, and Kore settles on a chunk radius. Chunks and spawning
 *    come after that.
 *
 * Runs on the UDP event loop, with the connection callbacks that drive it.
 */
class Session(
    private val config: ServerConfig,
    /** Every vanilla item, which the client needs before it can load the world. Shared by every session. */
    private val itemRegistry: ItemRegistry,
    private val networkId: String,
    /** The player's entity ID, unique across the server. */
    private val entityId: Long,
    /** The key the client proved when it connected, which its login must match. */
    private val offerKey: ECPublicKey?,
    private val authenticator: LoginAuthenticator,
    private val send: (ByteArray) -> Unit,
) {
    private enum class State { AWAITING_NETWORK_SETTINGS_REQUEST, AWAITING_LOGIN, NEGOTIATING_RESOURCE_PACKS, LOADING_WORLD, CLOSED }

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

    /** How many chunks around the player to send, once the client has asked. */
    var chunkRadius: Int? = null
        private set

    fun receive(message: ByteArray) {
        val packets = try {
            batches.decode(message).map(::decodeClientPacket)
        } catch (e: PacketException) {
            log.warn("Dropped a batch from network {}: {}", networkId, e.message)
            return
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
        val spawn = config.spawn
        send(
            StartGame(
                entityId = entityId,
                runtimeId = entityId,
                playerGameMode = config.gameMode,
                position = Vec3(spawn.x + 0.5f, spawn.y + EYE_HEIGHT, spawn.z + 0.5f),
                seed = config.seed,
                worldGameMode = config.gameMode,
                difficulty = config.difficulty,
                worldSpawn = spawn,
                worldName = config.level,
                serverVersion = config.version,
            ),
            itemRegistry,
        )
        state = State.LOADING_WORLD
        log.info("{} is loading the world, spawning at {}", player?.displayName, spawn)
    }

    /** Settles the view distance: what the client wants, within what it can handle and what Kore allows. */
    private fun onRequestChunkRadius(packet: RequestChunkRadius) {
        val wanted = if (packet.maxRadius > 0) minOf(packet.radius, packet.maxRadius) else packet.radius
        val radius = wanted.coerceIn(MIN_CHUNK_RADIUS, config.viewDistance)
        chunkRadius = radius
        send(ChunkRadiusUpdated(radius))
        log.debug("{} asked for {} chunks and gets {}", player?.displayName, packet.radius, radius)
    }

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
    private fun send(vararg packets: ServerPacket) = send(batches.encode(packets.map { it.encode() }))
}

/** Every connected player's session, driven by the transport's connection callbacks on the UDP event loop. */
class Sessions(private val config: ServerConfig, vanilla: VanillaData, private val authenticator: LoginAuthenticator) {
    private val sessions = HashMap<NetherNetConnection, Session>()
    private var nextEntityId = 1L

    /** Built here, on start, so no player's login pays to encode it. */
    private val itemRegistry = ItemRegistry(vanilla.items)

    fun connect(connection: NetherNetConnection) {
        sessions[connection] = Session(config, itemRegistry, connection.networkId, nextEntityId++, connection.clientKey, authenticator) { message ->
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
