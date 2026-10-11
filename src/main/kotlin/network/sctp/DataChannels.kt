package org.koremc.network.sctp

import java.nio.ByteBuffer

// Payload protocol identifiers for WebRTC (RFC 8831 §8). Empty messages travel as one byte under their own PPID.
private const val PPID_DCEP = 50
private const val PPID_STRING = 51
private const val PPID_BINARY = 53
private const val PPID_STRING_EMPTY = 56
private const val PPID_BINARY_EMPTY = 57

// Data Channel Establishment Protocol (RFC 8832).
private const val DATA_CHANNEL_OPEN = 0x03
private const val DATA_CHANNEL_ACK = 0x02
private const val CHANNEL_UNORDERED = 0x80
private const val CHANNEL_PARTIAL_RELIABLE_REXMIT = 0x01

/**
 * A data channel the client opened. NetherNet uses two: `ReliableDataChannel`
 * (ordered, reliable) and `UnreliableDataChannel` (unordered, no retransmits).
 */
class DataChannel internal constructor(
    val streamId: Int,
    val label: String,
    val ordered: Boolean,
    /** Null for reliable channels. */
    val maxRetransmits: Int?,
    private val association: SctpAssociation,
) {
    fun send(message: ByteArray) {
        if (message.isEmpty()) {
            association.send(streamId, PPID_BINARY_EMPTY, byteArrayOf(0), ordered, maxRetransmits)
        } else {
            association.send(streamId, PPID_BINARY, message, ordered, maxRetransmits)
        }
    }
}

sealed interface ChannelEvent {
    class Opened(val channel: DataChannel) : ChannelEvent
    class Message(val channel: DataChannel, val data: ByteArray) : ChannelEvent
}

/**
 * The data channels on one association. The client opens each channel in
 * band with DATA_CHANNEL_OPEN on a stream of its choosing, and Kore
 * acknowledges it on the same stream.
 */
class DataChannels(val association: SctpAssociation) {
    private val channels = HashMap<Int, DataChannel>()

    operator fun get(label: String): DataChannel? = channels.values.firstOrNull { it.label == label }

    /** Interprets a message from the association. Returns null for anything that isn't for an open channel. */
    fun receive(message: SctpMessage): ChannelEvent? = when (message.ppid) {
        PPID_DCEP -> open(message)
        PPID_BINARY, PPID_STRING -> channels[message.streamId]?.let { ChannelEvent.Message(it, message.payload) }
        PPID_BINARY_EMPTY, PPID_STRING_EMPTY -> channels[message.streamId]?.let { ChannelEvent.Message(it, ByteArray(0)) }
        else -> null
    }

    private fun open(message: SctpMessage): ChannelEvent? {
        val payload = ByteBuffer.wrap(message.payload)
        if (payload.remaining() < 12 || payload.get(0).toInt() != DATA_CHANNEL_OPEN) return null

        val channelType = payload.get(1).toInt() and 0xFF
        val labelLength = payload.getShort(8).toInt() and 0xFFFF
        if (payload.remaining() < 12 + labelLength) return null

        // Timed reliability isn't something NetherNet asks for, so those channels are treated as reliable.
        val channel = DataChannel(
            streamId = message.streamId,
            label = String(message.payload, 12, labelLength, Charsets.UTF_8),
            ordered = channelType and CHANNEL_UNORDERED == 0,
            maxRetransmits = payload.getInt(4).takeIf { channelType and 0x7F == CHANNEL_PARTIAL_RELIABLE_REXMIT },
            association = association,
        )
        channels[message.streamId] = channel
        association.send(message.streamId, PPID_DCEP, byteArrayOf(DATA_CHANNEL_ACK.toByte()))
        return ChannelEvent.Opened(channel)
    }
}
