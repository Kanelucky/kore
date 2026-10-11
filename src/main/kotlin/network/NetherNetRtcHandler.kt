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
import org.koremc.network.sctp.ChannelEvent
import org.koremc.network.sctp.DataChannel
import org.koremc.network.sctp.DataChannels
import org.koremc.network.sctp.Scheduler
import org.koremc.network.sctp.SctpAssociation
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
 * Kore runs ICE-lite, so the STUN side only answers the client's checks.
 * Every address that passes a check is a valid path for that client, and
 * replies follow the path the client is currently using. Inside DTLS, SCTP
 * carries the data channels, NetherNet framing rides on those, and every
 * complete message goes to [onMessage] on this event loop.
 */
class NetherNetRtcHandler(
    private val peers: Peers,
    private val dtls: DtlsContext,
    private val onMessage: (connection: NetherNetConnection, message: ByteArray) -> Unit = { _, _ -> },
) : SimpleChannelInboundHandler<DatagramPacket>() {
    // Only touched on this channel's event loop.
    private val byAddress = HashMap<InetSocketAddress, Peer>()
    private val connections = HashMap<Peer, NetherNetConnection>()

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

        // Any address that passes a check is a valid path for this client, not just the nominated one.
        if (peer.validatedAddresses.add(sender)) byAddress[sender] = peer

        // The client picks the path. Until it nominates one, the first address that checks in will do.
        if (request.useCandidate || peer.address == null) select(peer, sender)
    }

    private fun select(peer: Peer, address: InetSocketAddress) {
        if (peer.address == address) return
        peer.address = address
        log.info("ICE path for network {}: {}", peer.networkId, address)
    }

    private fun onDtls(ctx: ChannelHandlerContext, sender: InetSocketAddress, datagram: ByteBuffer) {
        val peer = byAddress[sender] ?: return

        // The client's ICE agent can move to a better path at any time, and may start sending on it
        // before it nominates it. Replies follow whichever validated path the client is using now.
        select(peer, sender)

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

        for (packet in received) {
            onSctp(ctx, peer, transport, packet)
            if (peer.channels?.association?.isClosed == true) {
                log.info("Network {} closed its SCTP association", peer.networkId)
                drop(peer)
                return
            }
        }
    }

    private fun onSctp(ctx: ChannelHandlerContext, peer: Peer, transport: DtlsTransport, packet: ByteBuffer) {
        val channels = peer.channels
            ?: DataChannels(SctpAssociation(transport::write, ctx.scheduler())).also { peer.channels = it }
        val association = channels.association

        val wasEstablished = association.isEstablished
        val messages = association.receive(packet)
        if (!wasEstablished && association.isEstablished) log.info("SCTP association up for network {}", peer.networkId)

        for (message in messages) {
            when (val event = channels.receive(message)) {
                is ChannelEvent.Opened -> onChannelOpened(peer, event.channel)
                is ChannelEvent.Message -> onChannelMessage(peer, event.channel, event.data)
                null -> Unit
            }
        }
    }

    private fun onChannelOpened(peer: Peer, channel: DataChannel) {
        val connection = connections.getOrPut(peer) { NetherNetConnection(peer.networkId, peer.maxMessageSize) }
        if (!connection.bind(channel)) {
            log.warn("Network {} opened '{}', which NetherNet doesn't use", peer.networkId, channel.label)
            return
        }
        log.info(
            "Network {} opened '{}' on stream {} ({}, {})",
            peer.networkId, channel.label, channel.streamId,
            if (channel.ordered) "ordered" else "unordered",
            channel.maxRetransmits?.let { "max $it retransmits" } ?: "reliable",
        )
    }

    private fun onChannelMessage(peer: Peer, channel: DataChannel, data: ByteArray) {
        val connection = connections[peer] ?: return
        val message = try {
            connection.receive(channel, data) ?: return
        } catch (e: FramingException) {
            log.warn("Dropped a message from network {}: {}", peer.networkId, e.message)
            return
        }
        log.debug("{}-byte message on '{}' from network {}", message.size, channel.label, peer.networkId)
        onMessage(connection, message)
    }

    private fun sweep() {
        val cutoff = System.nanoTime() - PEER_TIMEOUT.inWholeNanoseconds
        for (peer in peers.removeIdle(cutoff)) {
            forget(peer)
            log.info(if (peer.address == null) "Network {} never connected" else "Network {} timed out", peer.networkId)
        }
    }

    private fun drop(peer: Peer) {
        peers.remove(peer)
        forget(peer)
    }

    private fun forget(peer: Peer) {
        peer.validatedAddresses.forEach { byAddress.remove(it, peer) }
        peer.channels?.association?.close()
        connections.remove(peer)
    }
}

/** Runs SCTP timers on this channel's event loop, alongside everything else for the association. */
private fun ChannelHandlerContext.scheduler() = Scheduler { delay, task ->
    val future = executor().schedule(Runnable { task() }, delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    return@Scheduler { future.cancel(false) }
}
