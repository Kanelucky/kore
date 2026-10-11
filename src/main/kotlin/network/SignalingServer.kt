package org.koremc.network

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import java.net.SocketAddress

/** SDP offers are a few KB at most; anything near this limit is not a real client. */
private const val MAX_CONTENT_LENGTH = 64 * 1024

/**
 * The TCP half of NetherNet: a plain HTTP server that answers the client's
 * signaling requests. Gameplay traffic moves to a separate WebRTC (UDP)
 * connection once the SDP exchange completes.
 *
 * [handler] must be `@Sharable`, since one instance serves every connection.
 */
class SignalingServer(
    private val address: SocketAddress,
    private val handler: ChannelHandler,
) : AutoCloseable {
    private val bossGroup = NioEventLoopGroup(1)
    private val workerGroup = NioEventLoopGroup()

    fun bind(): Channel = try {
        ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline()
                        .addLast("http-codec", HttpServerCodec())
                        .addLast("http-aggregator", HttpObjectAggregator(MAX_CONTENT_LENGTH))
                        .addLast("nethernet-signaling", handler)
                }
            })
            .bind(address)
            .sync()
            .channel()
    } catch (e: Exception) {
        close()
        throw e
    }

    override fun close() {
        listOf(bossGroup, workerGroup)
            .map { it.shutdownGracefully() }
            .forEach { it.syncUninterruptibly() }
    }
}
