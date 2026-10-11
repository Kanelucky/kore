package org.koremc

import org.koremc.auth.AuthService
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
import org.koremc.registry.VanillaData
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.NoSuchFileException
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("Kore")

fun main() {
    val config = ServerConfig()
    val vanilla = try {
        VanillaData.load(config.dataDirectory)
    } catch (e: NoSuchFileException) {
        log.error("Missing {}. Kore needs the game data for {} in {}: clone https://github.com/KoreMC/data there.", e.file, config.version, config.dataDirectory.toAbsolutePath())
        exitProcess(1)
    }
    log.info("Loaded {} items, {} block states and {} biomes for {}", vanilla.items.size, vanilla.blocks.states.size, vanilla.biomes.size, config.version)

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
    val sessions = Sessions(config, vanilla, LoginAuthenticator(auth.verifier, config.onlineMode))

    val signaling = SignalingServer(
        address = InetSocketAddress(config.port),
        handler = NetherNetSignalingHandler(
            joinInfo = { config.toJoinInfo(onlinePlayers = 0) },
            answerOffer = { networkId, offer ->
                val clientKey = offer.verifiedClientKey(requireIdentity = config.onlineMode)
                localPeer.answer(offer).also { answer ->
                    peers.add(Peer(networkId, answer.ice, offer.iceUfrag, offer.fingerprints, offer.maxSendableMessageSize, clientKey))
                }
            },
        ),
    )
    val rtc = RtcServer(
        address = InetSocketAddress(config.port),
        handler = NetherNetRtcHandler(
            peers = peers,
            dtls = DtlsContext(certificate),
            onConnect = sessions::connect,
            onMessage = sessions::receive,
            onDisconnect = sessions::disconnect,
        ),
    )

    Runtime.getRuntime().addShutdownHook(Thread({ signaling.close(); rtc.close(); auth.close() }, "kore-shutdown"))

    try {
        val tcp = signaling.bind()
        rtc.bind()
        log.info("Kore is listening for NetherNet on {} (TCP signaling, UDP gameplay)", tcp.localAddress())
        tcp.closeFuture().syncUninterruptibly()
    } finally {
        signaling.close()
        rtc.close()
        auth.close()
    }
}
