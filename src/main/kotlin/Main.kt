package org.koremc

import io.netty.channel.nio.NioEventLoopGroup
import org.koremc.auth.AuthService
import org.koremc.command.Console
import org.koremc.network.NetherNetRtcHandler
import org.koremc.network.NetherNetSignalingHandler
import org.koremc.network.RtcServer
import org.koremc.network.SignalingServer
import org.koremc.network.rtc.DtlsCertificate
import org.koremc.network.rtc.DtlsContext
import org.koremc.network.rtc.LocalPeer
import org.koremc.network.rtc.OperatorIdentity
import org.koremc.network.rtc.Peer
import org.koremc.network.rtc.Peers
import org.koremc.network.rtc.hostCandidates
import org.koremc.network.rtc.localAddresses
import org.koremc.network.rtc.maxSendableMessageSize
import org.koremc.network.rtc.verifiedClientKey
import org.koremc.registry.ItemStacks
import org.koremc.registry.VanillaData
import org.koremc.storage.FlatLayers
import org.koremc.storage.WorldSettings
import org.koremc.storage.WorldStorage
import org.koremc.world.FlatWorld
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.NoSuchFileException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("Kore")

fun main() {
    // A crash on any thread lands in the log file too, not just the console.
    Thread.setDefaultUncaughtExceptionHandler { thread, e -> log.error("Uncaught exception on {}", thread.name, e) }
    val config = try {
        loadServerConfig(SERVER_PROPERTIES).withKoreSettings(KORE_JSON)
    } catch (e: IllegalStateException) {
        log.error(e.message)
        exitProcess(1)
    }
    val vanilla = try {
        VanillaData.load(config.dataDirectory)
    } catch (e: NoSuchFileException) {
        log.error("Missing {}. Kore needs the game data for {} in {}: clone https://github.com/KoreMC/data there.", e.file, config.version, config.dataDirectory.toAbsolutePath())
        exitProcess(1)
    }
    log.info(
        "Loaded {} items, {} block states, {} biomes, {} recipes and {} enchantments for {}",
        vanilla.items.size, vanilla.blocks.states.size, vanilla.biomes.size, vanilla.recipes.recipes.size, vanilla.enchantments.all.size, config.version,
    )

    val addresses = config.advertisedAddresses.map(InetAddress::getByName).ifEmpty { localAddresses() }
    val candidates = hostCandidates(config.port, addresses)
    if (candidates.isEmpty()) log.warn("No routable addresses found; clients will have nowhere to connect")
    candidates.forEach { log.info("ICE candidate: {}", it) }

    val certificate = DtlsCertificate.generate()
    val localPeer = LocalPeer(
        certificate = certificate,
        identity = OperatorIdentity.loadOrCreate(config.operatorKeyFile, config.operatorDomain, issuer = config.name),
        candidates = candidates,
    )
    // Offline mode never contacts Minecraft's services, so it works without an internet connection.
    val auth = AuthService(config.version)
    if (config.onlineMode) auth.start() else log.warn("Offline mode: players aren't authenticated, so anyone can join as anyone")

    val peers = Peers()
    val items = ItemStacks(vanilla.items, vanilla.creative, vanilla.blocks)
    val settings = WorldSettings(
        config.gameMode, config.difficulty, config.allowCheats, config.seed ?: Random.nextLong(), config.version, config.protocol, config.forceGameMode, config.levelType,
    )
    val storage = try {
        WorldStorage.open(config.worldsDirectory.resolve(config.level), config.level, FlatLayers(FlatWorld.vanillaLayers(vanilla.blocks)), vanilla.blocks, items, settings, vanilla.traits)
    } catch (e: Exception) {
        log.error("Couldn't open the world in {}", config.worldsDirectory.resolve(config.level).toAbsolutePath(), e)
        exitProcess(1)
    }
    // A world made before keeps its own game mode, unless force-gamemode says otherwise, and its own seed.
    val running = config.copy(gameMode = storage.settings.gameMode, seed = storage.settings.seed)
    // One thread carries every client's UDP traffic and runs every session, so the world needs no locks.
    val loop = NioEventLoopGroup(1)
    // Completes when the server should stop: when `/stop` runs, or the signaling server closes.
    val stopping = CompletableFuture<Unit>()
    // Built once, on start, so no player's login pays to encode the definitions.
    val sessions = Sessions(
        running, GameData.of(vanilla, storage.world, items, players = storage), LoginAuthenticator(auth.verifier, config.onlineMode), loop.next(),
        stop = { stopping.complete(Unit) }, saveWorld = storage::save,
    )

    // Saves the world one last time, on the sessions' thread as it always is, and tells everyone still on that the
    // server closed, then closes the world, however the server stops. It goes first, as closing the gameplay server
    // stops that thread.
    val closed = AtomicBoolean(false)
    fun closeWorld() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { loop.submit(sessions::shutDown).get(SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS) }.onFailure { log.error("Couldn't save the world as the server stopped", it) }
        storage.close()
        log.info("Saved the world")
    }

    val signaling = SignalingServer(
        address = InetSocketAddress(config.port),
        handler = NetherNetSignalingHandler(
            joinInfo = { running.toJoinInfo(onlinePlayers = 0) },
            answerOffer = { networkId, offer ->
                val clientKey = offer.verifiedClientKey(requireIdentity = config.onlineMode)
                localPeer.answer(offer).also { answer ->
                    peers.add(Peer(networkId, answer.ice, offer.iceUfrag, offer.fingerprints, offer.maxSendableMessageSize, clientKey))
                }
            },
        ),
    )
    val rtc = RtcServer(
        group = loop,
        address = InetSocketAddress(config.port),
        handler = NetherNetRtcHandler(
            peers = peers,
            dtls = DtlsContext(certificate),
            onConnect = sessions::connect,
            onMessage = sessions::receive,
            onDisconnect = sessions::disconnect,
        ),
    )

    Runtime.getRuntime().addShutdownHook(Thread({ closeWorld(); signaling.close(); rtc.close(); auth.close() }, "kore-shutdown"))

    try {
        val tcp = signaling.bind()
        rtc.bind()
        log.info("Kore is listening for NetherNet on {} (TCP signaling, UDP gameplay)", tcp.localAddress())
        Console(sessions::runConsole).start()
        tcp.closeFuture().addListener { stopping.complete(Unit) }
        stopping.join()
    } finally {
        closeWorld()
        signaling.close()
        rtc.close()
        auth.close()
    }
}

/** How long the last save waits for the sessions' thread before the server stops without it. */
private const val SAVE_TIMEOUT_SECONDS = 30L
