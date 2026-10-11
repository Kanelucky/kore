package org.koremc

import org.koremc.network.NetherNetSignalingHandler
import org.koremc.network.SignalingServer
import org.koremc.network.rtc.DtlsCertificate
import org.koremc.network.rtc.LocalPeer
import org.koremc.network.rtc.OperatorIdentity
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

    val localPeer = LocalPeer(
        certificate = DtlsCertificate.generate(),
        identity = OperatorIdentity.loadOrCreate(config.operatorKeyFile, config.operatorDomain, issuer = config.name),
        candidates = candidates,
    )

    val server = SignalingServer(
        address = InetSocketAddress(config.port),
        handler = NetherNetSignalingHandler(
            joinInfo = { config.toJoinInfo(onlinePlayers = 0) },
            answerOffer = localPeer::answer,
        ),
    )

    Runtime.getRuntime().addShutdownHook(Thread(server::close, "kore-shutdown"))

    val channel = server.bind()
    log.info("Kore is listening for NetherNet signaling on {}", channel.localAddress())

    channel.closeFuture().syncUninterruptibly()
}
