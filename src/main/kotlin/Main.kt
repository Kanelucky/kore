package org.koremc

import org.koremc.network.NetherNetSignalingHandler
import org.koremc.network.SignalingServer
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress

private val log = LoggerFactory.getLogger("Kore")

fun main() {
    val config = ServerConfig()
    val server = SignalingServer(
        address = InetSocketAddress(config.port),
        handler = NetherNetSignalingHandler { config.toJoinInfo(onlinePlayers = 0) },
    )

    Runtime.getRuntime().addShutdownHook(Thread(server::close, "kore-shutdown"))

    val channel = server.bind()
    log.info("Kore is listening for NetherNet signaling on {}", channel.localAddress())

    channel.closeFuture().syncUninterruptibly()
}
