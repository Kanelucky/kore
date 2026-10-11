package org.koremc.network

import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import java.net.SocketAddress

/**
 * The UDP half of NetherNet: a single socket that carries every client's
 * ICE, DTLS and SCTP traffic once signaling is done.
 */
class RtcServer(
    private val address: SocketAddress,
    private val handler: ChannelHandler,
) : AutoCloseable {
    private val group = NioEventLoopGroup(1)

    fun bind(): Channel = Bootstrap()
        .group(group)
        .channel(NioDatagramChannel::class.java)
        .handler(handler)
        .bind(address)
        .sync()
        .channel()

    override fun close() {
        group.shutdownGracefully().syncUninterruptibly()
    }
}
