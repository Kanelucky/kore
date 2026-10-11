package org.koremc.network

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.util.AttributeKey
import java.net.SocketAddress

/** SDP offers are a few KB at most; anything near this limit is not a real client. */
private const val MAX_CONTENT_LENGTH = 64 * 1024

/** The first byte of a TLS record that carries a handshake message, such as a ClientHello. */
private const val TLS_HANDSHAKE = 0x16

/** Set on connections that opened with a TLS handshake: the game trying HTTPS before plain HTTP. */
internal val TRIED_HTTPS: AttributeKey<Boolean> = AttributeKey.valueOf("kore.triedHttps")

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
                        .addLast("https-attempts", HttpsAttemptDetector())
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

/**
 * Marks a connection that opens with a TLS handshake, then steps out of the
 * pipeline. The game tries HTTPS before falling back to plain HTTP, so the
 * request it makes is routine rather than malformed. It's still answered
 * the same way, so the fallback carries on as before.
 */
internal class HttpsAttemptDetector : ChannelInboundHandlerAdapter() {
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is ByteBuf && msg.isReadable && msg.getUnsignedByte(msg.readerIndex()).toInt() == TLS_HANDSHAKE) {
            ctx.channel().attr(TRIED_HTTPS).set(true)
        }
        ctx.pipeline().remove(this)
        ctx.fireChannelRead(msg)
    }
}
