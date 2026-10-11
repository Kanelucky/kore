package org.koremc.network

import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.socket.DatagramPacket
import org.koremc.network.rtc.DtlsContext
import org.koremc.network.rtc.DtlsTransport
import org.koremc.network.rtc.Peer
import org.koremc.network.rtc.Peers
import org.koremc.network.rtc.bindingSuccess
import org.koremc.network.rtc.parseStun
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger(NetherNetRtcHandler::class.java)

/** Connected clients send consent checks every few seconds (RFC 7675). This long without one and they're gone. */
private val PEER_TIMEOUT = 30.seconds
private val SWEEP_INTERVAL = 5.seconds

/**
 * Handles everything that arrives on the UDP socket. STUN and DTLS share the
 * port and are told apart by their first byte (RFC 7983).
 *
 * Kore runs ICE-lite, so the STUN side only answers the client's checks. The
 * address the client nominates becomes its address, and DTLS records from
 * that address belong to it.
 */
class NetherNetRtcHandler(
    private val peers: Peers,
    private val dtls: DtlsContext,
) : SimpleChannelInboundHandler<DatagramPacket>() {
    // Only touched on this channel's event loop.
    private val byAddress = HashMap<InetSocketAddress, Peer>()

    override fun channelActive(ctx: ChannelHandlerContext) {
        val interval = SWEEP_INTERVAL.inWholeMilliseconds
        ctx.executor().scheduleAtFixedRate(::sweep, interval, interval, TimeUnit.MILLISECONDS)
        super.channelActive(ctx)
    }

    override fun channelRead0(ctx: ChannelHandlerContext, packet: DatagramPacket) {
        val content = packet.content()
        if (!content.isReadable) return

        when (content.getUnsignedByte(content.readerIndex()).toInt()) {
            in 0..3 -> onStun(ctx, packet.sender(), ByteBufUtil.getBytes(content))
            in 20..63 -> onDtls(ctx, packet.sender(), content.nioBuffer())
        }
    }

    private fun onStun(ctx: ChannelHandlerContext, sender: InetSocketAddress, bytes: ByteArray) {
        val request = parseStun(bytes)?.takeIf { it.isBindingRequest } ?: return

        // Checks to Kore carry "<Kore's ufrag>:<client's ufrag>".
        val ufrags = request.username?.split(':', limit = 2)?.takeIf { it.size == 2 } ?: return
        val peer = peers[ufrags[0]]?.takeIf { it.remoteUfrag == ufrags[1] } ?: return
        if (!request.isAuthentic(peer.localIce.pwd)) {
            log.debug("Bad STUN integrity from {} for network {}", sender, peer.networkId)
            return
        }

        val response = bindingSuccess(request, sender, peer.localIce.pwd)
        ctx.writeAndFlush(DatagramPacket(Unpooled.wrappedBuffer(response), sender))
        peer.lastSeen = System.nanoTime()

        // The client picks the path. Until it nominates one, the first address that checks in will do.
        if (request.useCandidate || peer.address == null) select(peer, sender)
    }

    private fun select(peer: Peer, address: InetSocketAddress) {
        if (peer.address == address) return
        peer.address?.let { byAddress.remove(it) }
        peer.address = address
        byAddress[address] = peer
        log.info("ICE path for network {}: {}", peer.networkId, address)
    }

    private fun onDtls(ctx: ChannelHandlerContext, sender: InetSocketAddress, datagram: ByteBuffer) {
        val peer = byAddress[sender] ?: return
        val transport = peer.dtls ?: DtlsTransport(dtls.newEngine(peer.remoteFingerprints)) { record ->
            peer.address?.let { ctx.writeAndFlush(DatagramPacket(Unpooled.wrappedBuffer(record), it)) }
        }.also { peer.dtls = it }

        val wasConnected = transport.isConnected
        val received = try {
            transport.receive(datagram)
        } catch (e: SSLException) {
            log.warn("DTLS failed for network {}: {}", peer.networkId, e.message)
            drop(peer)
            return
        }

        if (!wasConnected && transport.isConnected) {
            log.info("DTLS connected for network {} ({})", peer.networkId, transport.cipherSuite)
        }
        if (transport.isClosed) {
            log.info("Network {} closed its DTLS connection", peer.networkId)
            drop(peer)
            return
        }

        // SCTP rides inside DTLS and is the next layer to build. For now, show that it arrives.
        received.forEach { log.debug("SCTP packet from network {} ({} bytes)", peer.networkId, it.remaining()) }
    }

    private fun sweep() {
        val cutoff = System.nanoTime() - PEER_TIMEOUT.inWholeNanoseconds
        for (peer in peers.removeIdle(cutoff)) {
            peer.address?.let { byAddress.remove(it) }
            log.info(if (peer.address == null) "Network {} never connected" else "Network {} timed out", peer.networkId)
        }
    }

    private fun drop(peer: Peer) {
        peers.remove(peer)
        peer.address?.let { byAddress.remove(it) }
    }
}
