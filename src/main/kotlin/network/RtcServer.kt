package org.koremc.network

import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.EventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import java.net.SocketAddress

/**
 * The UDP half of NetherNet: a single socket that carries every client's
 * ICE, DTLS and SCTP traffic once signaling is done, on the threads of
 * [group]. Closing the server shuts them down.
 */
class RtcServer(
    private val group: EventLoopGroup,
    private val address: SocketAddress,
    private val handler: ChannelHandler,
) : AutoCloseable {

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
