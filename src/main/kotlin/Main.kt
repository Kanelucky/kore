package org.koremc

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
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.InetSocketAddress

private val log = LoggerFactory.getLogger("Kore")

fun main() {
    val config = ServerConfig()

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
    val peers = Peers()

    val signaling = SignalingServer(
        address = InetSocketAddress(config.port),
        handler = NetherNetSignalingHandler(
            joinInfo = { config.toJoinInfo(onlinePlayers = 0) },
            answerOffer = { networkId, offer ->
                localPeer.answer(offer).also { answer ->
                    peers.add(Peer(networkId, answer.ice, offer.iceUfrag, offer.fingerprints))
                }
            },
        ),
    )
    val rtc = RtcServer(
        address = InetSocketAddress(config.port),
        handler = NetherNetRtcHandler(peers, DtlsContext(certificate)),
    )

    Runtime.getRuntime().addShutdownHook(Thread({ signaling.close(); rtc.close() }, "kore-shutdown"))

    try {
        val tcp = signaling.bind()
        rtc.bind()
        log.info("Kore is listening for NetherNet on {} (TCP signaling, UDP gameplay)", tcp.localAddress())
        tcp.closeFuture().syncUninterruptibly()
    } finally {
        signaling.close()
        rtc.close()
    }
}
