package org.koremc.network

import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.socket.DatagramPacket
import org.koremc.network.rtc.InvalidOfferException
import org.koremc.network.rtc.SdpAnswer
import org.koremc.network.rtc.SdpOffer
import org.koremc.network.rtc.parseOffer
import org.koremc.protocol.writeString
import org.koremc.protocol.writeVarInt
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.text.Charsets.UTF_8

private val log = LoggerFactory.getLogger(LanDiscovery::class.java)

/** The port games look for each other on, on the local network. */
const val LAN_DISCOVERY_PORT = 7551

// The kinds of discovery packet: a game looking for others, an answer saying what's here, and a message for one in particular.
private const val REQUEST = 0
private const val RESPONSE = 1
private const val MESSAGE = 2

/** The length of a packet's HMAC, before what it's of. */
private const val CHECKSUM_SIZE = 32

/** What comes before a packet's body: its length, kind, sender and eight bytes of nothing. */
private const val HEADER_SIZE = 20

private const val AES_BLOCK = 16

/** The version of what a server says of itself that the game reads now. */
private const val SERVER_DATA_VERSION = 7

/** How the game says a server is reached, as it numbers them: by NetherNet. */
private const val NETHERNET_TRANSPORT = 4

/** The reason the game gives a connection the other side couldn't make. */
private const val CONNECTION_FAILED = 11

/** The key every game encrypts and signs discovery packets with: the SHA-256 of a fixed application ID, little-endian. */
private val KEY: ByteArray = MessageDigest.getInstance("SHA-256").digest(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0xDEADBEEFL).array())

/** A discovery packet: its kind, who sent it, by their network ID, and its body. */
internal class DiscoveryPacket(val kind: Int, val sender: Long, val body: ByteArray)

/**
 * A discovery packet as games send it: an HMAC-SHA256 of the packet, then
 * the packet, padded and encrypted with AES-256 in ECB mode, both with
 * [KEY]. The packet is its length, as a little-endian short, its kind, as
 * another, its sender's network ID, eight bytes of nothing, and [body].
 */
internal fun encodeDiscovery(kind: Int, sender: Long, body: ByteArray): ByteArray {
    val packet = ByteBuffer.allocate(HEADER_SIZE + body.size).order(ByteOrder.LITTLE_ENDIAN)
        .putShort((HEADER_SIZE + body.size).toShort())
        .putShort(kind.toShort())
        .putLong(sender)
        .putLong(0)
        .put(body)
        .array()
    val padding = AES_BLOCK - packet.size % AES_BLOCK
    val padded = packet + ByteArray(padding) { padding.toByte() }
    return hmac(packet) + aes(Cipher.ENCRYPT_MODE).doFinal(padded)
}

/** The discovery packet in [datagram], or null if it isn't one, or its HMAC doesn't match. */
internal fun decodeDiscovery(datagram: ByteArray): DiscoveryPacket? {
    val encrypted = datagram.copyOfRange(minOf(CHECKSUM_SIZE, datagram.size), datagram.size)
    if (encrypted.isEmpty() || encrypted.size % AES_BLOCK != 0) return null
    val padded = aes(Cipher.DECRYPT_MODE).doFinal(encrypted)
    val padding = padded.last().toInt() and 0xFF
    if (padding !in 1..AES_BLOCK || padded.takeLast(padding).any { (it.toInt() and 0xFF) != padding }) return null
    val packet = padded.copyOf(padded.size - padding)
    if (packet.size < HEADER_SIZE || !MessageDigest.isEqual(hmac(packet), datagram.copyOf(CHECKSUM_SIZE))) return null
    val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
    val length = buffer.short.toInt() and 0xFFFF
    // Some games count the length without itself.
    if (length != packet.size && length != packet.size - 2) return null
    val kind = buffer.short.toInt() and 0xFFFF
    val sender = buffer.long
    if (buffer.long != 0L) return null
    return DiscoveryPacket(kind, sender, packet.copyOfRange(HEADER_SIZE, packet.size))
}

private fun hmac(data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(KEY, "HmacSHA256")) }.doFinal(data)

private fun aes(mode: Int): Cipher = Cipher.getInstance("AES/ECB/NoPadding").apply { init(mode, SecretKeySpec(KEY, "AES")) }

/**
 * Makes the server one the game finds on the local network, as it finds a
 * dedicated server or another player's world: it answers games looking,
 * on [LAN_DISCOVERY_PORT], with what [status] says of it, and lets them
 * join through messages there, rather than HTTP, a connection request
 * carrying the game's offer, which [answerOffer] answers, as for HTTP. The
 * client's ICE candidates come after, which Kore, running ICE-lite, has
 * no use for. The server is [networkId] on the network, and accepts
 * players who aren't signed in unless [onlineMode].
 */
class LanDiscovery(
    private val networkId: Long,
    private val onlineMode: Boolean,
    private val status: () -> JoinInfo,
    private val answerOffer: (networkId: String, offer: SdpOffer) -> SdpAnswer,
) : SimpleChannelInboundHandler<DatagramPacket>() {
    /** Which time the server's said what it is, which the game tells servers apart by, besides their network IDs. */
    private val advertisement = "%016x".format(Random.nextLong())

    override fun channelRead0(ctx: ChannelHandlerContext, datagram: DatagramPacket) {
        val packet = decodeDiscovery(ByteBufUtil.getBytes(datagram.content())) ?: return
        if (packet.sender == networkId) return // Its own answers, heard back
        when (packet.kind) {
            REQUEST -> ctx.reply(datagram, encodeDiscovery(RESPONSE, networkId, serverData(status(), onlineMode, advertisement)))
            MESSAGE -> {
                val body = ByteBuffer.wrap(packet.body).order(ByteOrder.LITTLE_ENDIAN)
                if (body.remaining() < 12 || body.long != networkId) return
                val length = body.int
                if (length < 0 || length > body.remaining()) return
                val signal = String(packet.body, 12, length, UTF_8)
                onSignal(ctx, datagram, packet.sender, signal)
            }
        }
    }

    /**
     * Answers a signal from the game [sender]: a connection request, with
     * its offer, gets a connection response, with Kore's answer, or an error.
     * Its ICE candidates, and the pings games keep each other up with, need
     * nothing.
     */
    private fun onSignal(ctx: ChannelHandlerContext, datagram: DatagramPacket, sender: Long, signal: String) {
        val parts = signal.split(' ', limit = 3)
        if (parts.size < 3 || parts[0] != "CONNECTREQUEST") return
        val connection = parts[1]
        val reply = try {
            "CONNECTRESPONSE $connection ${answerOffer(sender.toULong().toString(), parseOffer(parts[2])).encode()}"
        } catch (e: InvalidOfferException) {
            log.warn("Rejected a LAN offer from network {}: {}", sender.toULong(), e.message)
            "CONNECTERROR $connection $CONNECTION_FAILED"
        }
        log.info("Answered network {} over LAN discovery", sender.toULong())
        val text = reply.toByteArray(UTF_8)
        val body = ByteBuffer.allocate(12 + text.size).order(ByteOrder.LITTLE_ENDIAN).putLong(sender).putInt(text.size).put(text).array()
        ctx.reply(datagram, encodeDiscovery(MESSAGE, networkId, body))
    }

    private fun ChannelHandlerContext.reply(datagram: DatagramPacket, bytes: ByteArray) {
        writeAndFlush(DatagramPacket(Unpooled.wrappedBuffer(bytes), datagram.sender()))
    }
}

/**
 * What a server says of itself to games looking, [info], as the game's own
 * do: its name, protocol and version, level, players and the most, its
 * game mode, whether it's an editor world or hardcore, which it isn't,
 * that it's on the network, whether it takes players who aren't signed in,
 * unless [onlineMode], which [advertisement] this is, and how it's reached.
 * The game reads it as hex, after its length.
 */
internal fun serverData(info: JoinInfo, onlineMode: Boolean, advertisement: String): ByteArray {
    val data = Unpooled.buffer()
    data.writeByte(SERVER_DATA_VERSION)
    data.writeString(info.name)
    data.writeVarInt(info.protocol)
    data.writeString(info.version)
    data.writeString(info.level)
    data.writeVarInt(info.players)
    data.writeVarInt(info.maxPlayers)
    data.writeVarInt(info.gameType)
    data.writeBoolean(false) // An editor world
    data.writeBoolean(false) // Hardcore
    data.writeBoolean(true) // On the local network
    data.writeBoolean(!onlineMode)
    data.writeString(advertisement)
    data.writeVarInt(NETHERNET_TRANSPORT)
    val hex = ByteBufUtil.hexDump(data).toByteArray(UTF_8)
    return ByteBuffer.allocate(4 + hex.size).order(ByteOrder.LITTLE_ENDIAN).putInt(hex.size).put(hex).array()
}
