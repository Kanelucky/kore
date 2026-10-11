package org.koremc

import org.koremc.inventory.HOTBAR_SIZE
import org.koremc.inventory.PlayerInventory
import org.koremc.inventory.StackRequestHandler
import org.koremc.network.DisconnectReason
import org.koremc.network.NetherNetConnection
import org.koremc.protocol.ActorDataId
import org.koremc.protocol.ActorEvent
import org.koremc.protocol.ActorFlag
import org.koremc.protocol.AddItemActor
import org.koremc.protocol.AddPlayer
import org.koremc.protocol.Animate
import org.koremc.protocol.Attribute
import org.koremc.protocol.BlockAction
import org.koremc.protocol.BatchCodec
import org.koremc.protocol.ByteItem
import org.koremc.protocol.ClientCacheStatus
import org.koremc.protocol.ChunkRadiusUpdated
import org.koremc.protocol.ClientPacket
import org.koremc.protocol.ContainerClose
import org.koremc.protocol.ContainerOpen
import org.koremc.protocol.Disconnect
import org.koremc.protocol.FloatItem
import org.koremc.protocol.GameMode
import org.koremc.protocol.Interact
import org.koremc.protocol.InventorySlot
import org.koremc.protocol.InventoryAction
import org.koremc.protocol.InventoryTransaction
import org.koremc.protocol.ItemStackRequest
import org.koremc.protocol.ItemStackResponse
import org.koremc.protocol.ItemUse
import org.koremc.protocol.ItemUseAction
import org.koremc.protocol.InputFlag
import org.koremc.protocol.JigsawStructureData
import org.koremc.protocol.LevelChunk
import org.koremc.protocol.LevelEvent
import org.koremc.protocol.LevelSoundEvent
import org.koremc.protocol.Login
import org.koremc.protocol.LoginRequest
import org.koremc.protocol.MobEquipment
import org.koremc.protocol.MoveActorAbsolute
import org.koremc.protocol.MovePlayer
import org.koremc.protocol.NetworkChunkPublisherUpdate
import org.koremc.protocol.NetworkSettings
import org.koremc.protocol.PacketException
import org.koremc.protocol.PermissionLevel
import org.koremc.protocol.PlayStatus
import org.koremc.protocol.PlayerAction
import org.koremc.protocol.PlayerAuthInput
import org.koremc.protocol.PlayerList
import org.koremc.protocol.PlayerListEntry
import org.koremc.protocol.RequestChunkRadius
import org.koremc.protocol.RemoveActor
import org.koremc.protocol.RequestNetworkSettings
import org.koremc.protocol.ResourcePackClientResponse
import org.koremc.protocol.ResourcePackStack
import org.koremc.protocol.ResourcePacksInfo
import org.koremc.protocol.ServerPacket
import org.koremc.protocol.SetActorData
import org.koremc.protocol.SetLocalPlayerAsInitialised
import org.koremc.protocol.ShortItem
import org.koremc.protocol.ShowEquipment
import org.koremc.protocol.Skin
import org.koremc.protocol.StartGame
import org.koremc.protocol.StringItem
import org.koremc.protocol.TakeItemActor
import org.koremc.protocol.UpdateAbilities
import org.koremc.protocol.UpdateAttributes
import org.koremc.protocol.UpdateBlock
import org.koremc.protocol.abilitiesFor
import org.koremc.protocol.VoxelShapes
import org.koremc.protocol.decodeClientPacket
import org.koremc.protocol.encode
import org.koremc.protocol.flagsItem
import org.koremc.protocol.parseConnectionRequest
import org.koremc.protocol.skinFrom
import org.koremc.registry.AIR
import org.koremc.registry.AIR_NETWORK_ID
import org.koremc.registry.BlockState
import org.koremc.registry.ItemStack
import org.koremc.world.BlockPos
import org.koremc.world.ChunkPos
import org.koremc.world.Face
import org.koremc.world.ItemEntity
import org.koremc.world.PLAYER_EYE_HEIGHT
import org.koremc.world.PLAYER_HEIGHT
import org.koremc.world.PLAYER_MAX_AIR
import org.koremc.world.PLAYER_SNEAKING_HEIGHT
import org.koremc.world.PLAYER_WALK_SPEED
import org.koremc.world.PLAYER_WIDTH
import org.koremc.world.Vec3
import org.koremc.world.blockPlacedBy
import org.koremc.world.breakBlock
import org.koremc.world.breakTicks
import org.koremc.world.corner
import org.koremc.world.centre
import org.koremc.world.chunksAround
import org.koremc.world.facing
import org.koremc.world.harvests
import org.koremc.world.place
import org.koremc.world.releaseButton
import org.koremc.world.side
import org.koremc.world.tickItems
import org.koremc.world.updateAround
import org.koremc.world.usable
import org.koremc.world.use
import org.slf4j.LoggerFactory
import java.security.interfaces.ECPublicKey
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private val log = LoggerFactory.getLogger(Session::class.java)

/** Every batch in and out, by packet. The console leaves it out, and each run's log file has it all. */
private val packetLog = LoggerFactory.getLogger("org.koremc.packets")

/** Batches this size or larger are compressed. Small ones cost more to compress than they save. */
private const val COMPRESSION_THRESHOLD = 256

/** How long the game's tick is. */
private const val TICK_MILLIS = 50L

/** How long an item that drops from a block waits before anyone can pick it up, in ticks. */
private const val DROPPED_PICKUP_DELAY = 10

/** How long an item a player throws waits, long enough for it to clear them: two seconds. */
private const val THROWN_PICKUP_DELAY = 40

/** How fast a thrown item leaves the player's hand, in blocks a tick, and the lift it gets. */
private const val THROW_SPEED = 0.3
private const val THROW_LIFT = 0.1f

/** How high above where an item lies the client puts it, an eighth of a block. */
private const val ITEM_NETWORK_OFFSET = 0.125f

/** How far players reach to pick items up: a block either way of the item, and half a block up and down. */
private const val PICKUP_REACH = 1f
private const val PICKUP_REACH_UP = 0.5f

/** An item entity's half width and height. */
private const val ITEM_HALF_WIDTH = 0.125f
private const val ITEM_HEIGHT = 0.25f

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

/** The window and container type of a player's own inventory. */
private const val INVENTORY_WINDOW = 0
private const val INVENTORY_CONTAINER = 0xFF

/** The window a client closes when chat opens over a container. It gets no reply. */
private const val CHAT_WINDOW = 0xFF

/**
 * How far players reach to build and break, from their eyes to the
 * block's middle, in and out of creative. These are a little beyond the
 * game's own reach, so a client at its limit isn't turned down.
 */
private const val SURVIVAL_REACH = 8f
private const val CREATIVE_REACH = 14f

/** How far cracks have to spread for a block to break, in the units `LevelEvent` counts their pace in. */
private const val CRACKED = 65535

/** The device a player plays on, when their client doesn't say. */
private const val UNKNOWN_PLATFORM = -1

/** The flags a player always has: they breathe, fall, collide and climb. */
private val PLAYER_FLAGS = setOf(ActorFlag.BREATHING, ActorFlag.HAS_GRAVITY, ActorFlag.HAS_COLLISION, ActorFlag.CAN_CLIMB)

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
 *    then the biomes and the creative inventory.
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
 * into it. Its flags say when the player sprints and sneaks, which Kore
 * mirrors in the player's actor data.
 *
 * Players build and break as the client predicts, which Kore checks and
 * then shows to everyone who has the chunk. Placing comes as an inventory
 * transaction using the held item on a block. Breaking comes as one riding
 * in `PlayerAuthInput`, or in creative as a `PlayerAction`. Clicking a
 * lever, button, door, trapdoor or fence gate uses it instead of placing
 * against it, unless the player sneaks with something in hand.
 *
 * A survival player's input also carries the steps of breaking a block:
 * starting, a crack every tick, and stopping. Kore shows everyone with the
 * chunk the cracks spreading at the pace the block breaks at, and checks a
 * reported break didn't come too soon after the player started on it.
 *
 * Players see each other. Each tick, the client hears who's on the player
 * list, with their skins, and sees the players in the chunks it has appear,
 * move, sneak, change what they hold and go. Their arm swings reach it as
 * they come in.
 *
 * Runs on the UDP event loop, with the connection callbacks that drive it.
 */
class Session(
    private val config: ServerConfig,
    /** The world and the game's definitions, shared by every session. */
    private val game: GameData,
    private val networkId: String,
    /** The player's entity ID, unique across the server. */
    val entityId: Long,
    /** The key the client proved when it connected, which its login must match. */
    private val offerKey: ECPublicKey?,
    private val authenticator: LoginAuthenticator,
    /** Every player's session, this one included, for showing them what this player changes in the world. */
    private val viewers: () -> Collection<Session>,
    /** Runs a task a number of ticks from now, on the same thread as the sessions, like a button popping back out. */
    private val schedule: (ticks: Int, task: () -> Unit) -> Unit,
    private val send: (ByteArray) -> Unit,
) {
    private enum class State { AWAITING_NETWORK_SETTINGS_REQUEST, AWAITING_LOGIN, NEGOTIATING_RESOURCE_PACKS, LOADING_WORLD, SPAWNING, IN_GAME, CLOSED }

    private val batches = BatchCodec()
    private val world = game.world
    private val inventory = PlayerInventory()
    private val stackRequests = StackRequestHandler(inventory, game.creative, creativeMode = { config.gameMode == GameMode.CREATIVE }, onDrop = ::throwItem)
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

    /** Whether the player is sprinting, as their input last said. */
    var sprinting = false
        private set

    /** Whether the player is sneaking, as their input last said. */
    var sneaking = false
        private set

    /** Whether the player is flying, where sneaking means descending rather than slowing down. */
    private var flying = false

    /** Which way the player looks, around the vertical, as the client last reported. Blocks they place turn by it. */
    var yaw = 0f
        private set

    /** How far the player looks up or down, as the client last reported. Items they throw go that way. */
    var pitch = 0f
        private set

    /** Which way the player's head faces, around the vertical, which can be a little off their body's. */
    var headYaw = 0f
        private set

    /** Whether the player stood on something, as their input last said. */
    var onGround = false
        private set

    /** How the player looks to everyone else, from their login. */
    private var skin: Skin? = null

    /** The players on the client's player list, by entity ID. */
    private val listedPlayers = HashMap<Long, UUID>()

    /** What the client was last shown of each other player in view, by entity ID. */
    private val shownPlayers = HashMap<Long, Look>()

    /** The items the client has been shown, by entity ID. */
    private val shownItems = HashSet<Long>()

    /** The hotbar slot the player holds, as the client last said. */
    var heldSlot = 0
        private set

    /** The block a survival player is breaking, from the client tick they started, and how many ticks it takes. */
    private var breaking: Breaking? = null

    private class Breaking(val pos: BlockPos, val startTick: Long, val ticks: Int?)

    /** Whether the player's inventory is open, which the client can't do without Kore's say-so. */
    private var inventoryOpen = false

    /** The chunk the player's view is centred on. */
    private var viewCentre = world.spawn.chunk

    fun receive(message: ByteArray) {
        val packets = try {
            batches.decode(message).map(::decodeClientPacket)
        } catch (e: PacketException) {
            log.warn("Dropped a batch from network {}: {}", networkId, e.message)
            return
        }
        // Movement arrives every tick in game, so batches of nothing else only go to trace.
        if (state == State.IN_GAME && packets.all { it is PlayerAuthInput && it.onlyMoves }) {
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
                is PlayerAuthInput -> {
                    onMove(packet)
                    // The item use first: a block breaking this tick is checked against when the player
                    // started on it, before the step that ends the breaking clears that.
                    packet.itemUse?.let { onItemUse(it, packet.tick) }
                    packet.stackRequest?.let { send(ItemStackResponse(listOf(stackRequests.handle(it)))) }
                    onBlockActions(packet.blockActions, packet.tick)
                }
                is Interact -> if (packet.action == Interact.OPEN_INVENTORY) openInventory()
                is ContainerClose -> onContainerClose(packet)
                is ItemStackRequest -> send(ItemStackResponse(packet.requests.map(stackRequests::handle)))
                is InventoryTransaction -> onTransaction(packet)
                is PlayerAction -> if (packet.action == PlayerAction.CREATIVE_DESTROY_BLOCK) onBreak(packet.position, creativeOnly = true)
                is MobEquipment -> onEquip(packet)
                is Animate -> if (packet.action == Animate.SWING_ARM) viewers().forEach { it.showSwing(entityId, packet.swingSource) }
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
        skin = skinFrom(request.clientData, config.version)

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
            ResourcePackClientResponse.Response.DOWNLOADING_FINISHED -> send(ResourcePackStack(baseGameVersion = config.version))

            // Kore offered nothing, so there's nothing to download. Carry on as though the client had everything.
            ResourcePackClientResponse.Response.DOWNLOADING -> {
                log.debug("Network {} asked for packs Kore never offered: {}", networkId, packet.packs)
                send(ResourcePackStack(baseGameVersion = config.version))
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
                baseGameVersion = config.version,
                blockDefinitions = game.blockDefinitions,
            ),
            *game.definitions.toTypedArray(),
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
        yaw = packet.yaw
        pitch = packet.pitch
        headYaw = packet.headYaw
        onGround = InputFlag.VERTICAL_COLLISION in packet.inputs
        onInputs(packet)

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
     * Follows the player's sprinting, from the client's flags for starting
     * and stopping it, and their sneaking, from the flag set while they
     * sneak. Starting to sneak ends a sprint, unless the player is flying.
     * A change goes back to the client as the player's actor data, tagged
     * with the input's tick so the client applies it at the moment it
     * predicted it.
     *
     * The sprint's speed boost is left to the client, which already applies
     * it: with Kore sending a walking speed of 0.1, a logged test measured
     * sprint-jumping at the vanilla 7.1 blocks a second.
     */
    private fun onInputs(packet: PlayerAuthInput) {
        val inputs = packet.inputs
        val wasSprinting = sprinting
        val wasSneaking = sneaking

        if (InputFlag.START_FLYING in inputs) flying = true
        if (InputFlag.STOP_FLYING in inputs) flying = false
        if (InputFlag.START_SPRINTING in inputs) {
            sprinting = true
            sneaking = false
        }
        if (InputFlag.STOP_SPRINTING in inputs) sprinting = false
        if (InputFlag.SNEAKING in inputs != sneaking) {
            sneaking = !sneaking
            if (sneaking && !flying) sprinting = false
        }

        if (sprinting == wasSprinting && sneaking == wasSneaking) return
        send(SetActorData(entityId, listOf(flagsItem(playerFlags()), FloatItem(ActorDataId.HEIGHT, playerHeight())), packet.tick))
        log.debug("{} is now {}sprinting and {}sneaking", player?.displayName, if (sprinting) "" else "not ", if (sneaking) "" else "not ")
    }

    private fun playerFlags() = PLAYER_FLAGS +
        listOfNotNull(ActorFlag.SPRINTING.takeIf { sprinting }, ActorFlag.SNEAKING.takeIf { sneaking })

    private fun playerHeight() = if (sneaking) PLAYER_SNEAKING_HEIGHT else PLAYER_HEIGHT

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

    /**
     * Opens the player's inventory, which with server-authoritative
     * inventories the client only does when Kore says so. The client can
     * ask again before the first answer arrives, and two answers crash it.
     */
    private fun openInventory() {
        if (inventoryOpen) return
        inventoryOpen = true
        send(ContainerOpen(INVENTORY_WINDOW, INVENTORY_CONTAINER, position.toBlockPos(), entityId = -1))
    }

    /** Agrees to the client closing a window, except one closed by chat opening, which needs no answer. */
    private fun onContainerClose(packet: ContainerClose) {
        when (packet.windowId) {
            INVENTORY_WINDOW -> {
                inventoryOpen = false
                send(ContainerClose(INVENTORY_WINDOW, containerType = 0))
            }
            CHAT_WINDOW -> inventoryOpen = false
            else -> send(ContainerClose(packet.windowId, packet.containerType))
        }
    }

    /** Follows the hotbar slot the player holds. The offhand reports its item this way too, and changes nothing. */
    private fun onEquip(packet: MobEquipment) {
        if (packet.windowId == INVENTORY_WINDOW && packet.selectedSlot in 0 until HOTBAR_SIZE) heldSlot = packet.selectedSlot
    }

    /** A transaction from before item stack requests, which the client still sends for a few things. */
    private fun onTransaction(packet: InventoryTransaction) {
        when (packet.type) {
            InventoryTransaction.USE_ITEM -> packet.use?.let(::onItemUse)
            InventoryTransaction.NORMAL -> onDropFromSlot(packet.actions)
        }
    }

    /**
     * Throws items from one of the player's slots, as pressing Q outside
     * the inventory screen does: one item, or the whole stack with Ctrl. On
     * mobile, the slot needn't be the one held. The client sends it as two
     * slot changes it has already made, the items leaving the slot and going
     * into the world, rather than as an item stack request. Kore throws them
     * if what it has in the slot is what the client had there. Either way,
     * the client hears what the slot holds now, which puts back a drop Kore
     * refused.
     */
    private fun onDropFromSlot(actions: List<InventoryAction>) {
        val drop = actions.singleOrNull { it.source == InventoryAction.WORLD && it.slot == InventoryAction.DROP_SLOT }
        val from = actions.singleOrNull { it.source == InventoryAction.CONTAINER && it.windowId == INVENTORY_WINDOW }
        if (actions.size != 2 || drop == null || from == null || from.slot !in 0 until inventory.items.size) {
            log.debug("Ignored a transaction of {} from {}", actions, player?.displayName)
            return
        }
        val there = inventory.items[from.slot]
        val count = drop.after.count
        // The client's stack must be Kore's: the same item, and as many of it.
        val same = there != null && there.stack.item.runtimeId == from.before.runtimeId && there.stack.count == from.before.count
        if (same && count in 1..there.stack.count && config.gameMode != GameMode.SPECTATOR) {
            inventory.remove(from.slot, count)
            throwItem(there.stack.copy(count = count))
        } else {
            log.debug("Refused {} dropping {} of {} from slot {}", player?.displayName, count, there?.stack?.item?.name, from.slot)
        }
        send(slotOf(from.slot))
    }

    /** The player using their item, at the client's [tick] when it came in their input. */
    private fun onItemUse(use: ItemUse, tick: Long? = null) {
        when (use.action) {
            ItemUseAction.PLACE -> onClick(use)
            ItemUseAction.DESTROY -> onBreak(use.position, creativeOnly = false, tick)
            // Using an item in the air, like eating, and attacking with one are for later.
            ItemUseAction.USE, ItemUseAction.USE_AS_ATTACK -> {}
        }
    }

    /**
     * A click on a block, with whatever the player holds, which the client
     * reports once it has predicted what happens. Clicking a block that
     * does something, like a lever, uses it. Otherwise, or when the player
     * sneaks with something in hand, the click places what they hold.
     *
     * The client checks some things Kore doesn't, like whether a torch has
     * a face to stand on, or whether a block would go inside its player.
     * When it finds a click does nothing, it changes nothing, and Kore
     * leaves it at that. While the player holds the button down, the
     * client tries again every frame, so that's often.
     */
    private fun onClick(use: ItemUse) {
        if (!use.predictedToWork) return
        val usable = world.stateAt(use.position)?.usable == true
        if (usable && !(sneaking && use.heldItem != 0)) useBlock(use.position) else onPlace(use)
    }

    /**
     * Uses the block at [pos], if the player can reach it and isn't a
     * spectator. Everyone with the chunk sees what changes and hears it. A
     * pressed button pops back out on its own, later. A use Kore refuses
     * puts back what the client predicted.
     */
    private fun useBlock(pos: BlockPos) {
        val used = if (config.gameMode != GameMode.SPECTATOR && inReach(pos)) world.use(pos, facing(yaw)) else null
        if (used == null) {
            log.debug("Refused {} using {} at {}", player?.displayName, world.stateAt(pos)?.name, pos)
            send(UpdateBlock(pos, world.blockAt(pos)))
            return
        }
        if (used.changed.isNotEmpty()) {
            val updates = used.changed.map { UpdateBlock(it, world.blockAt(it)) }
            val sound = used.sound?.let { LevelSoundEvent(it, pos.centre, used.soundData) }
            showAround(pos, *(updates + listOfNotNull(sound)).toTypedArray())
        }
        used.releaseIn?.let { ticks -> schedule(ticks) { releaseButton(pos) } }
        log.debug("{} used {} at {}", player?.displayName, world.stateAt(pos)?.name, pos)
    }

    /** Lets a button pop back out, for everyone with its chunk to see and hear, if it's still pressed in. */
    private fun releaseButton(pos: BlockPos) {
        if (!world.releaseButton(pos)) return
        showAround(pos, UpdateBlock(pos, world.blockAt(pos)), LevelSoundEvent(LevelSoundEvent.BUTTON_CLICK_OFF, pos.centre, world.blockAt(pos)))
    }

    /**
     * Places the block the player holds against the face they clicked, as
     * the client predicted, if they can: their game mode builds, the spot
     * is in reach, and the client's item is the block item Kore has in
     * that hotbar slot. Everyone with the chunk sees the block appear and
     * hears it go down, along with any blocks around it that join it, like
     * fences. Outside creative, placing uses up one of the stack.
     *
     * Kore's rules for where blocks go and which way they turn don't cover
     * all of the client's. So the client also hears what's really at both
     * spots it may have changed, the block clicked and the space beyond the
     * face, which puts right any guess that differed.
     */
    private fun onPlace(use: ItemUse) {
        val face = Face.of(use.face) ?: return
        if (use.hotbarSlot in 0 until HOTBAR_SIZE) heldSlot = use.hotbarSlot
        // The client's item must be the one Kore has in the slot.
        val stack = inventory.items[heldSlot]?.stack?.takeIf { use.hotbarSlot == heldSlot && it.item.runtimeId == use.heldItem }
        val block = stack?.let { it.block ?: world.blocks.blockPlacedBy(it.item.name) }
        val placed = if (block != null && canBuild() && inReach(use.position.side(face))) {
            world.place(block, use.position, face, use.clickPosition.y, facing(yaw))
        } else {
            emptyList()
        }

        if (placed.isEmpty()) {
            log.debug("Refused {} placing {} against {} {}", player?.displayName, block?.name ?: "item ${use.heldItem}", use.position, face)
        } else {
            val first = placed.first()
            val sound = first to LevelSoundEvent(LevelSoundEvent.PLACE, first.centre, world.blockAt(first))
            showAround(placed.map(::updateOf) + sound + world.updateAround(placed).map(::updateOf))
            if (!creative) inventory.remove(heldSlot)
            log.debug("{} placed {} at {}", player?.displayName, world.stateAt(first)?.name, first)
        }
        val corrections = (listOf(use.position, use.position.side(face)) - placed.toSet()).map { UpdateBlock(it, world.blockAt(it)) }
        if (corrections.isNotEmpty()) send(*corrections.toTypedArray())
        // Outside creative, the client used up the item it placed, which it gets back if nothing was placed.
        if (placed.isEmpty() && !creative) send(slotOf(heldSlot))
    }

    private val creative get() = config.gameMode == GameMode.CREATIVE

    /**
     * Breaks the block at [pos], as the client predicted, if the player
     * can: their game mode breaks blocks and it's in reach. A [creativeOnly]
     * break, like creative's instant one, needs the player to be in
     * creative. Outside creative, the block mustn't be one only creative
     * players break, and a break reported at the client's [tick] mustn't
     * come too soon after the player started on it.
     *
     * Everyone with the chunk sees the block go, with its particles and
     * sound: the player too, since the client leaves those to the server.
     * So do any torches the block held up, and blocks around it that joined
     * it, like fences, change. A break Kore refuses puts the block back.
     */
    private fun onBreak(pos: BlockPos, creativeOnly: Boolean, tick: Long? = null) {
        val state = world.stateAt(pos)
        // Air is what a break leaves, so it's a break the client has already reported another way.
        if (state?.name == AIR) return

        val creative = config.gameMode == GameMode.CREATIVE
        val ticks = state?.let { ticksToBreak(it.name) }
        val unbreakable = state != null && game.mining[state.name]?.unbreakable == true
        // Checked before the cracks stop, which forgets when the player started.
        val early = !creative && tooSoon(pos, ticks, tick)
        if (!creative) stopBreaking()
        if (state == null || !canBuild() || !inReach(pos) || (!creative && (creativeOnly || unbreakable || early))) {
            log.debug("Refused {} breaking {} at {}", player?.displayName, state?.name, pos)
            send(UpdateBlock(pos, world.blockAt(pos)))
            return
        }
        val broken = world.breakBlock(pos)
        val effects = broken.flatMap { (at, block) -> listOf(at to UpdateBlock(at, AIR_NETWORK_ID), at to LevelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, at.centre, block.networkId)) }
        showAround(effects + world.updateAround(broken.keys).map(::updateOf))
        if (!creative) broken.forEach(::dropFrom)
        log.debug("{} broke {} at {}", player?.displayName, state.name, pos)
    }

    /**
     * Drops what [block], broken at [pos], drops, if what the player holds
     * harvests it: each stack somewhere near the block's middle, popping
     * up and out a little, as the game drops them.
     */
    private fun dropFrom(pos: BlockPos, block: BlockState) {
        if (game.mining[block.name]?.let { harvests(it, heldItem()) } == false) return
        for (stack in game.drops.of(block)) {
            fun jitter() = Random.nextFloat() * 0.5f - 0.25f
            val at = Vec3(pos.x + 0.5f + jitter(), pos.y + 0.5f - ITEM_HEIGHT / 2 + jitter(), pos.z + 0.5f + jitter())
            val velocity = Vec3(Random.nextFloat() * 0.2f - 0.1f, 0.2f, Random.nextFloat() * 0.2f - 0.1f)
            world.spawnItem(stack, at, velocity, DROPPED_PICKUP_DELAY)
        }
    }

    /**
     * Throws [stack] the way the player looks, from just below their eyes,
     * as dropping an item from the inventory does.
     */
    private fun throwItem(stack: ItemStack) {
        val yaw = Math.toRadians(yaw.toDouble())
        val pitch = Math.toRadians(pitch.toDouble())
        val velocity = Vec3(
            (-sin(yaw) * cos(pitch) * THROW_SPEED).toFloat(),
            (-sin(pitch) * THROW_SPEED).toFloat() + THROW_LIFT,
            (cos(yaw) * cos(pitch) * THROW_SPEED).toFloat(),
        )
        world.spawnItem(stack, Vec3(position.x, position.y - 0.3f, position.z), velocity, THROWN_PICKUP_DELAY)
        log.debug("{} dropped {} {}", player?.displayName, stack.count, stack.item.name)
    }

    /**
     * Picks up every item in the player's reach that's ready to be, as much
     * of each as fits. The player sees their slots fill, and everyone with
     * the chunk sees the item fly to them. Run once a tick.
     */
    fun collectItems() {
        if (state != State.IN_GAME || config.gameMode == GameMode.SPECTATOR) return
        for (item in world.items.toList()) {
            if (item.pickupDelay > 0 || !inPickupReach(item)) continue
            val (slots, left) = inventory.add(item.stack)
            if (slots.isEmpty()) continue
            send(*slots.map(::slotOf).toTypedArray())
            showAround(item.position.toBlockPos(), TakeItemActor(item.id, entityId))
            log.debug("{} picked up {} {}", player?.displayName, item.stack.count - left, item.stack.item.name)
            if (left == 0) world.removeItem(item.id) else item.stack = item.stack.copy(count = left)
        }
    }

    /** Whether [item] is in the player's reach: its box, grown by [PICKUP_REACH] across and [PICKUP_REACH_UP] up and down, meets theirs. */
    private fun inPickupReach(item: ItemEntity): Boolean {
        val feet = position.y - PLAYER_EYE_HEIGHT
        val across = PLAYER_WIDTH / 2 + ITEM_HALF_WIDTH + PICKUP_REACH
        return abs(item.position.x - position.x) < across && abs(item.position.z - position.z) < across &&
            item.position.y - PICKUP_REACH_UP < feet + playerHeight() && item.position.y + ITEM_HEIGHT + PICKUP_REACH_UP > feet
    }

    /**
     * Shows the client the items lying in the chunks it has: those it hasn't
     * seen appear, those that moved move, those whose stacks changed size
     * change, and those that are gone, picked up, merged or out of view, go.
     * Run once a tick.
     */
    fun syncItems() {
        if (state != State.IN_GAME) return
        val inView = world.items.filter { it.position.toBlockPos().chunk in sentChunks }
        val ids = inView.mapTo(HashSet()) { it.id }
        val packets = shownItems.filter { it !in ids }.map(::RemoveActor).toMutableList<ServerPacket>()
        for (item in inView) {
            if (item.id !in shownItems) {
                packets += AddItemActor(item.id, item.stack, item.position, item.velocity)
                continue
            }
            if (item.moved) packets += MoveActorAbsolute(item.id, Vec3(item.position.x, item.position.y + ITEM_NETWORK_OFFSET, item.position.z), item.onGround)
            if (item.restacked) packets += ActorEvent(item.id, ActorEvent.UPDATE_STACK_SIZE, item.stack.count)
        }
        shownItems.retainAll(ids)
        shownItems += ids
        if (packets.isNotEmpty()) send(*packets.toTypedArray())
    }

    /**
     * What others see of a player at a moment: where they are and which way
     * they look, whether they sneak or sprint, and what they hold.
     */
    private data class Look(
        val position: Vec3,
        val pitch: Float,
        val yaw: Float,
        val headYaw: Float,
        val onGround: Boolean,
        val sneaking: Boolean,
        val sprinting: Boolean,
        val heldSlot: Int,
        val held: ItemStack?,
    )

    private fun look() = Look(position, pitch, yaw, headYaw, onGround, sneaking, sprinting, heldSlot, inventory.items[heldSlot]?.stack)

    /**
     * Shows the client the other players. Everyone in the game is on its
     * player list, this player included, which is where the client finds
     * their skins. Those in the chunks it has appear, move, sneak, sprint,
     * change what they hold and go, as they do. Run once a tick.
     */
    fun syncPlayers() {
        if (state != State.IN_GAME) return
        val players = viewers().filter { it.isInGame }
        val packets = mutableListOf<ServerPacket>()

        val ids = players.mapTo(HashSet()) { it.entityId }
        val entries = mutableListOf<PlayerListEntry>()
        listedPlayers.entries.removeIf { (id, uuid) -> (id !in ids).also { gone -> if (gone) entries += PlayerListEntry.Remove(uuid) } }
        for (other in players) {
            if (other.entityId in listedPlayers) continue
            val entry = other.listEntry()
            listedPlayers[other.entityId] = entry.uuid
            entries += entry
        }
        if (entries.isNotEmpty()) packets += PlayerList(entries)

        val inView = players.filter { it !== this && it.position.toBlockPos().chunk in sentChunks }
        val viewIds = inView.mapTo(HashSet()) { it.entityId }
        for (gone in shownPlayers.keys.filter { it !in viewIds }) {
            shownPlayers.remove(gone)
            packets += RemoveActor(gone)
        }
        for (other in inView) {
            val now = other.look()
            val before = shownPlayers.put(other.entityId, now)
            if (before == null) {
                packets += other.addPlayer(now)
                log.debug("{} sees {}", player?.displayName, other.player?.displayName)
                continue
            }
            if (now.position != before.position || now.pitch != before.pitch || now.yaw != before.yaw || now.headYaw != before.headYaw || now.onGround != before.onGround) {
                packets += MovePlayer(other.entityId, now.position, now.pitch, now.yaw, now.headYaw, now.onGround)
            }
            if (now.sneaking != before.sneaking || now.sprinting != before.sprinting) {
                packets += SetActorData(other.entityId, listOf(flagsItem(shownFlags(now)), FloatItem(ActorDataId.HEIGHT, heightOf(now))))
            }
            if (now.held != before.held || now.heldSlot != before.heldSlot) packets += ShowEquipment(other.entityId, now.held, now.heldSlot)
        }
        if (packets.isNotEmpty()) send(*packets.toTypedArray())
    }

    /** This player's entry on everyone's player list. They must have logged in. */
    private fun listEntry(): PlayerListEntry.Add {
        val identity = checkNotNull(player) { "a player in the game has logged in" }
        return PlayerListEntry.Add(
            identity.uuid, entityId, identity.displayName, identity.xuid.orEmpty(),
            platform = login?.clientInfo?.deviceOs ?: UNKNOWN_PLATFORM,
            skin = checkNotNull(skin) { "a player in the game has a skin from their login" },
        )
    }

    /** This player, appearing to someone else as they [look]. */
    private fun addPlayer(look: Look): AddPlayer {
        val identity = checkNotNull(player) { "a player in the game has logged in" }
        val data = listOf(
            flagsItem(shownFlags(look)),
            StringItem(ActorDataId.NAME, identity.displayName),
            ByteItem(ActorDataId.ALWAYS_SHOW_NAME, 1),
            FloatItem(ActorDataId.SCALE, 1f),
            FloatItem(ActorDataId.WIDTH, PLAYER_WIDTH),
            FloatItem(ActorDataId.HEIGHT, heightOf(look)),
        )
        val feet = Vec3(look.position.x, look.position.y - PLAYER_EYE_HEIGHT, look.position.z)
        return AddPlayer(
            identity.uuid, identity.displayName, entityId, feet, look.pitch, look.yaw, look.headYaw, look.held,
            config.gameMode, data, abilitiesFor(config.gameMode), platform = login?.clientInfo?.deviceOs ?: UNKNOWN_PLATFORM,
        )
    }

    /** The flags others see a player with as they [look]: their own, and that their name shows above them. */
    private fun shownFlags(look: Look) = PLAYER_FLAGS + ActorFlag.SHOW_NAME + ActorFlag.ALWAYS_SHOW_NAME +
        listOfNotNull(ActorFlag.SPRINTING.takeIf { look.sprinting }, ActorFlag.SNEAKING.takeIf { look.sneaking })

    private fun heightOf(look: Look) = if (look.sneaking) PLAYER_SNEAKING_HEIGHT else PLAYER_HEIGHT

    /** Shows the client the player [entityId] swinging their arm, for [source], if it can see them. */
    private fun showSwing(entityId: Long, source: String?) {
        if (entityId in shownPlayers) send(Animate(Animate.SWING_ARM, entityId, swingSource = source))
    }

    /** The slot of the player's items numbered [slot], as it is now, for the client. */
    private fun slotOf(slot: Int) = inventory.items[slot].let { InventorySlot(slot, it?.stack, it?.id ?: 0) }

    private fun heldItem() = inventory.items[heldSlot]?.stack?.item?.name

    /**
     * Whether a break the client reported at its [tick] came sooner after
     * the player started on the block at [pos] than the [ticks] it takes.
     * The client counts its own ticks, so the gap is exact, but a quarter's
     * slack covers anything Kore's reckoning leaves out. A block that
     * breaks at once, or a break that came without a tick, isn't checked.
     */
    private fun tooSoon(pos: BlockPos, ticks: Int?, tick: Long?): Boolean {
        if (ticks == null || ticks <= 1 || tick == null) return false
        val started = breaking?.takeIf { it.pos == pos }
        val took = started?.let { tick - it.startTick }
        log.debug("{} broke a block at {} {} ticks after starting, of the {} it takes", player?.displayName, pos, took ?: "unknown", ticks)
        return took == null || took < ticks * 3 / 4
    }

    /**
     * Follows a survival player breaking blocks, from the steps their input
     * carries at the client's [tick]. Everyone with the chunk sees cracks
     * spread over a block the player starts on, at the pace it breaks at,
     * and bits fly off it each tick. In creative, blocks break at once, so
     * there's nothing to see.
     */
    private fun onBlockActions(actions: List<BlockAction>, tick: Long) {
        if (config.gameMode != GameMode.SURVIVAL) return
        for (action in actions) {
            when (action.action) {
                PlayerAction.START_DESTROY_BLOCK -> startBreaking(action.position, tick)
                PlayerAction.CRACK_BLOCK -> crack(action.position, action.face)
                PlayerAction.ABORT_DESTROY_BLOCK, PlayerAction.STOP_DESTROY_BLOCK -> stopBreaking()
            }
        }
    }

    private fun startBreaking(pos: BlockPos, tick: Long) {
        stopBreaking()
        val state = world.stateAt(pos)?.takeIf { it.name != AIR } ?: return
        val ticks = ticksToBreak(state.name)
        breaking = Breaking(pos, tick, ticks)
        if (ticks != null && ticks > 0) showAround(pos, LevelEvent(LevelEvent.START_BLOCK_CRACKING, pos.corner, CRACKED / ticks))
    }

    private fun crack(pos: BlockPos, face: Int) {
        if (face !in Face.entries.indices || world.stateAt(pos)?.name in listOf(null, AIR)) return
        showAround(pos, LevelEvent(LevelEvent.PARTICLES_CRACK_BLOCK_DOWN + face, pos.corner, world.blockAt(pos)))
    }

    /** Stops the cracks spreading over the block the player was breaking, if any. */
    private fun stopBreaking() {
        val stopped = breaking ?: return
        breaking = null
        showAround(stopped.pos, LevelEvent(LevelEvent.STOP_BLOCK_CRACKING, stopped.pos.corner, 0))
    }

    /** How many ticks the player takes to break the block called [name], with what they hold, if Kore knows. */
    private fun ticksToBreak(name: String): Int? = game.mining[name]?.let { breakTicks(it, heldItem()) }

    private fun canBuild() = config.gameMode == GameMode.SURVIVAL || config.gameMode == GameMode.CREATIVE

    private fun inReach(pos: BlockPos): Boolean {
        val reach = if (config.gameMode == GameMode.CREATIVE) CREATIVE_REACH else SURVIVAL_REACH
        val centre = pos.centre
        val dx = centre.x - position.x
        val dy = centre.y - position.y
        val dz = centre.z - position.z
        return dx * dx + dy * dy + dz * dz <= reach * reach
    }

    /** Sends [packets] about the block at [pos] to every player who has its chunk, this one included. */
    private fun showAround(pos: BlockPos, vararg packets: ServerPacket) = showAround(packets.map { pos to it })

    /**
     * Sends each packet, about the block it's paired with, to every player
     * who has that block's chunk, this one included: one batch for each
     * chunk, in order. The blocks a change reaches, like a torch on a
     * neighbour's side, can be in the next chunk.
     */
    private fun showAround(packets: List<Pair<BlockPos, ServerPacket>>) {
        for ((chunk, inChunk) in packets.groupBy { (pos, _) -> pos.chunk }) {
            val batch = inChunk.map { (_, packet) -> packet }.toTypedArray()
            viewers().forEach { it.show(chunk, *batch) }
        }
    }

    /** The block at [pos] as it is now, for the clients that have it. */
    private fun updateOf(pos: BlockPos): Pair<BlockPos, ServerPacket> = pos to UpdateBlock(pos, world.blockAt(pos))

    /** Sends [packets] about the chunk at [chunk], if the client has it. Without it, there's nothing for them to change. */
    fun show(chunk: ChunkPos, vararg packets: ServerPacket) {
        if (chunk in sentChunks) send(*packets)
    }

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
                flagsItem(playerFlags()),
                ShortItem(ActorDataId.AIR_SUPPLY, PLAYER_MAX_AIR),
                ShortItem(ActorDataId.AIR_SUPPLY_MAX, PLAYER_MAX_AIR),
                FloatItem(ActorDataId.SCALE, 1f),
                FloatItem(ActorDataId.WIDTH, PLAYER_WIDTH),
                FloatItem(ActorDataId.HEIGHT, playerHeight()),
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

/**
 * Every connected player's session, driven by the transport's connection
 * callbacks on the UDP event loop. The game ticks on the same thread, 20
 * times a second: items move, players pick up those in reach, then each
 * player sees what changed.
 */
class Sessions(
    private val config: ServerConfig,
    private val game: GameData,
    private val authenticator: LoginAuthenticator,
    /** The thread the sessions run on, which runs what the world does later, too. */
    private val loop: ScheduledExecutorService,
) {
    private val sessions = HashMap<NetherNetConnection, Session>()

    init {
        loop.scheduleAtFixedRate({ runCatching(::tick).onFailure { log.error("A tick failed", it) } }, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS)
    }

    private fun tick() {
        game.world.tickItems()
        sessions.values.forEach { it.collectItems() }
        sessions.values.forEach { it.syncItems() }
        sessions.values.forEach { it.syncPlayers() }
    }

    fun connect(connection: NetherNetConnection) {
        sessions[connection] = Session(
            config, game, connection.networkId, game.world.newEntityId(), connection.clientKey, authenticator,
            viewers = { sessions.values },
            // A task that throws would otherwise fail quietly, in a future nobody looks at.
            schedule = { ticks, task -> loop.schedule({ runCatching(task).onFailure { log.error("A scheduled task failed", it) } }, ticks * TICK_MILLIS, TimeUnit.MILLISECONDS) },
        ) { message ->
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
