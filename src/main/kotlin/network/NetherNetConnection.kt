package org.koremc.network

import org.koremc.network.sctp.DataChannel
import java.nio.ByteBuffer
import java.security.interfaces.ECPublicKey
import kotlin.math.min

private const val RELIABLE_CHANNEL = "ReliableDataChannel"
private const val UNRELIABLE_CHANNEL = "UnreliableDataChannel"

/** The one-byte header can count down from 255, so a message splits into at most 256 fragments. */
private const val MAX_FRAGMENTS = 256

/** The most Kore buffers while rejoining one message. Far beyond anything a client sends, but not unbounded. */
private const val MAX_REASSEMBLED_SIZE = 8 shl 20

/** Thrown when a client breaks NetherNet's framing rules. */
class FramingException(message: String) : Exception(message)

/** Why a connection ended. */
enum class DisconnectReason {
    /** The client closed its DTLS connection or SCTP association. */
    CLOSED_BY_CLIENT,

    /** The client stopped answering: no ICE consent check for 30 seconds. */
    TIMED_OUT,

    /** The client broke the DTLS protocol mid-connection. */
    PROTOCOL_ERROR,

    /** Kore's UDP socket closed, usually because the server is stopping. */
    SERVER_STOPPED,
}

/**
 * One client's NetherNet connection: its two data channels, with NetherNet's
 * framing on top (Mojang's NetherNet onboarding guide, §6.1).
 *
 * Every data channel message starts with a one-byte header: 0 for a whole
 * message or the last fragment, and N for a fragment with N more to come.
 * Only the reliable channel fragments. The unreliable channel carries
 * messages that fit whole, and NetherNet drops the rest.
 *
 * Not thread-safe. It lives on the UDP event loop with the rest of the connection.
 */
class NetherNetConnection internal constructor(
    val networkId: String,
    /** The largest data channel message the client accepts, header included. */
    private val maxFrameSize: Int,
    /**
     * The key the client proved it holds when it connected, by signing its
     * DTLS fingerprints with it. Its login must name the same key. Null if
     * the client sent no identity, which only offline mode allows.
     */
    val clientKey: ECPublicKey? = null,
) {
    private var reliable: DataChannel? = null
    private var unreliable: DataChannel? = null
    private val assembler = FrameAssembler()

    /** Whether the reliable channel is open. Nothing can be sent before it is. */
    val isOpen get() = reliable != null

    /**
     * Sends [message], split into fragments on the reliable channel if it's
     * too big for one. Returns false if the channel isn't open yet, or if an
     * unreliable message is too big to send whole.
     */
    fun send(message: ByteArray, reliable: Boolean = true): Boolean {
        val channel = (if (reliable) this.reliable else unreliable) ?: return false
        if (!reliable && message.size + 1 > maxFrameSize) return false
        frame(message, maxFrameSize).forEach(channel::send)
        return true
    }

    /** Takes a channel the client opened. Returns false for channels NetherNet doesn't use. */
    internal fun bind(channel: DataChannel): Boolean {
        when (channel.label) {
            RELIABLE_CHANNEL -> reliable = channel
            UNRELIABLE_CHANNEL -> unreliable = channel
            else -> return false
        }
        return true
    }

    /** Handles one data channel message, returning a complete NetherNet message if it finished one. */
    internal fun receive(channel: DataChannel, frame: ByteArray): ByteArray? = when (channel) {
        reliable -> assembler.add(frame)
        unreliable -> {
            if (frame.isEmpty() || frame[0].toInt() != 0) throw FramingException("unreliable messages can't be fragmented")
            frame.copyOfRange(1, frame.size)
        }
        else -> null
    }
}

/** Splits [message] into frames of at most [maxFrameSize] bytes, each led by its countdown header. */
internal fun frame(message: ByteArray, maxFrameSize: Int): List<ByteArray> {
    val fragmentSize = maxFrameSize - 1
    val count = maxOf(1, (message.size + fragmentSize - 1) / fragmentSize)
    require(count <= MAX_FRAGMENTS) { "a ${message.size}-byte message needs $count fragments, but NetherNet allows $MAX_FRAGMENTS" }

    return List(count) { index ->
        val start = index * fragmentSize
        val end = min(start + fragmentSize, message.size)
        ByteArray(1 + end - start).also {
            it[0] = (count - 1 - index).toByte()
            message.copyInto(it, destinationOffset = 1, startIndex = start, endIndex = end)
        }
    }
}

/** Rejoins fragments from the reliable channel. Each header says how many fragments are still to come. */
internal class FrameAssembler {
    private val fragments = mutableListOf<ByteArray>()
    private var size = 0
    private var expected = -1

    fun add(frame: ByteArray): ByteArray? {
        if (frame.isEmpty()) throw FramingException("message has no header")
        val header = frame[0].toInt() and 0xFF

        // Most messages fit whole and skip the bookkeeping.
        if (expected < 0 && header == 0) return frame.copyOfRange(1, frame.size)

        if (expected >= 0 && header != expected) {
            reset()
            throw FramingException("expected a fragment with $expected to come, got $header")
        }
        size += frame.size - 1
        if (size > MAX_REASSEMBLED_SIZE) {
            reset()
            throw FramingException("message is over $MAX_REASSEMBLED_SIZE bytes")
        }
        fragments += frame

        if (header > 0) {
            expected = header - 1
            return null
        }
        val message = ByteBuffer.allocate(size)
        fragments.forEach { message.put(it, 1, it.size - 1) }
        reset()
        return message.array()
    }

    private fun reset() {
        fragments.clear()
        size = 0
        expected = -1
    }
}
