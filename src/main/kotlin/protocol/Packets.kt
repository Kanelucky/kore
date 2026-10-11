package org.koremc.protocol

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled

// Packet IDs and layouts follow Mojang's protocol schemas for 1.26.50 (protocol 2193):
// https://github.com/Mojang/bedrock-protocol-docs
private const val LOGIN = 1
private const val PLAY_STATUS = 2
private const val DISCONNECT = 5
private const val NETWORK_SETTINGS = 143
private const val REQUEST_NETWORK_SETTINGS = 193

/** The low ten bits of a packet header hold its ID. The rest name split-screen sub-clients, which Kore doesn't support. */
private const val PACKET_ID_MASK = 0x3FF

/** A packet from the client. */
sealed interface ClientPacket

/** The first packet of every connection, carrying nothing but the client's protocol version. */
data class RequestNetworkSettings(val protocol: Int) : ClientPacket

/** The client's protocol version again, and its connection request: who it claims to be, plus its client data. */
class Login(val protocol: Int, val connectionRequest: ByteArray) : ClientPacket

/** A packet Kore doesn't decode yet. */
class UnhandledPacket(val id: Int, val payload: ByteArray) : ClientPacket {
    override fun toString() = "UnhandledPacket(id=$id, ${payload.size} bytes)"
}

/** A packet to the client. */
sealed interface ServerPacket {
    val id: Int
    fun encodeBody(out: ByteBuf)
}

/** Tells the client how to compress from now on. Everything after this packet, both ways, uses it. */
data class NetworkSettings(
    val compressionThreshold: Int,
    val compressionAlgorithm: CompressionAlgorithm = CompressionAlgorithm.ZLIB,
    val clientThrottle: Boolean = false,
    val clientThrottleThreshold: Int = 0,
    val clientThrottleScalar: Float = 0f,
) : ServerPacket {
    override val id get() = NETWORK_SETTINGS

    override fun encodeBody(out: ByteBuf) {
        out.writeShortLE(compressionThreshold)
        out.writeShortLE(compressionAlgorithm.id)
        out.writeBoolean(clientThrottle)
        out.writeByte(clientThrottleThreshold)
        out.writeFloatLE(clientThrottleScalar)
    }
}

data class PlayStatus(val status: Status) : ServerPacket {
    /** Declared in wire order: each status is sent as its ordinal. */
    enum class Status {
        LOGIN_SUCCESS,
        LOGIN_FAILED_CLIENT_OLD,
        LOGIN_FAILED_SERVER_OLD,
        PLAYER_SPAWN,
        LOGIN_FAILED_INVALID_TENANT,
        LOGIN_FAILED_EDITION_MISMATCH_EDU_TO_VANILLA,
        LOGIN_FAILED_EDITION_MISMATCH_VANILLA_TO_EDU,
        LOGIN_FAILED_SERVER_FULL_SUB_CLIENT,
        LOGIN_FAILED_EDITOR_MISMATCH_EDITOR_TO_VANILLA,
        LOGIN_FAILED_EDITOR_MISMATCH_VANILLA_TO_EDITOR,
    }

    override val id get() = PLAY_STATUS

    override fun encodeBody(out: ByteBuf) {
        out.writeInt(status.ordinal)
    }
}

/** Ends the client's session, showing [message] if there is one, or a stock screen for [reason] if not. */
data class Disconnect(
    val reason: Reason,
    val message: String? = null,
    /** The message with profanity filtered, for players who've asked for that. */
    val filteredMessage: String = message.orEmpty(),
) : ServerPacket {
    /** The handful of the client's 150 disconnect reasons Kore uses, with their wire values. */
    enum class Reason(val id: Int) {
        UNKNOWN(0),
        OUTDATED_SERVER(34),
        OUTDATED_CLIENT(35),
        NOT_AUTHENTICATED(46),
        KICKED(55),
    }

    override val id get() = DISCONNECT

    override fun encodeBody(out: ByteBuf) {
        out.writeVarInt(reason.id)
        // The messages are an optional variant: 0 says they follow, 1 says there are none.
        if (message == null) {
            out.writeByte(1)
        } else {
            out.writeByte(0)
            out.writeString(message)
            out.writeString(filteredMessage)
        }
    }
}

fun decodeClientPacket(packet: ByteArray): ClientPacket {
    val buffer = Unpooled.wrappedBuffer(packet)
    return try {
        when (val id = buffer.readVarUInt() and PACKET_ID_MASK) {
            REQUEST_NETWORK_SETTINGS -> RequestNetworkSettings(protocol = buffer.readInt())
            LOGIN -> Login(protocol = buffer.readInt(), connectionRequest = buffer.readByteArray())
            else -> UnhandledPacket(id, ByteBufUtil.getBytes(buffer))
        }
    } catch (e: IndexOutOfBoundsException) {
        throw PacketException("packet ends early")
    }
}

fun ServerPacket.encode(): ByteArray {
    val buffer = Unpooled.buffer()
    buffer.writeVarUInt(id)
    encodeBody(buffer)
    return ByteBufUtil.getBytes(buffer)
}
