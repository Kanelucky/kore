package org.koremc.protocol

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import java.util.UUID

// Packet IDs and layouts follow Mojang's protocol schemas for 1.26.50 (protocol 2193):
// https://github.com/Mojang/bedrock-protocol-docs
private const val LOGIN = 1
private const val PLAY_STATUS = 2
private const val DISCONNECT = 5
private const val RESOURCE_PACKS_INFO = 6
private const val RESOURCE_PACK_STACK = 7
private const val RESOURCE_PACK_CLIENT_RESPONSE = 8
private const val REQUEST_CHUNK_RADIUS = 69
private const val CHUNK_RADIUS_UPDATED = 70
private const val CLIENT_CACHE_STATUS = 129
private const val ITEM_REGISTRY = 162
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

/** Whether the client supports the blob cache, which saves resending chunk data it already has. */
data class ClientCacheStatus(val supported: Boolean) : ClientPacket

/** The client's answer during resource pack negotiation. */
data class ResourcePackClientResponse(
    val response: Response,
    /** The packs the client wants, when it's [Response.DOWNLOADING]. */
    val packs: List<String> = emptyList(),
) : ClientPacket {
    /** Declared in wire order. Each is sent as its ordinal, followed by its name as a string. */
    enum class Response {
        /** The client won't accept the packs and is leaving. */
        CANCEL,

        /** The client wants [packs] sent. */
        DOWNLOADING,

        /** The client has every pack it was offered. */
        DOWNLOADING_FINISHED,

        /** The client has applied the pack stack and is ready for the world. */
        RESOURCE_PACK_STACK_FINISHED,
    }
}

/** How many chunks around the player the client wants to see, and the most it can handle. */
data class RequestChunkRadius(val radius: Int, val maxRadius: Int) : ClientPacket

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

/**
 * Lists the resource packs the client needs before joining. Kore has none
 * yet, so it sends an empty list, and the client replies that it has
 * everything already.
 */
data class ResourcePacksInfo(
    val packsRequired: Boolean = false,
    val forceDisableVibrantVisuals: Boolean = false,
) : ServerPacket {
    override val id get() = RESOURCE_PACKS_INFO

    override fun encodeBody(out: ByteBuf) {
        out.writeBoolean(packsRequired)
        out.writeBoolean(false) // Has add-on packs
        out.writeBoolean(false) // Has client scripts
        out.writeBoolean(forceDisableVibrantVisuals)
        out.writeUuid(UUID(0, 0)) // No world template
        out.writeString("") // World template version
        out.writeVarUInt(0) // Resource packs
    }
}

/** The order to apply packs in. Kore's is empty: just the vanilla packs the client already has. */
data class ResourcePackStack(
    /** The vanilla packs to build on. `*` means whatever version the client has. */
    val baseGameVersion: String = "*",
    val experiments: Map<String, Boolean> = emptyMap(),
) : ServerPacket {
    override val id get() = RESOURCE_PACK_STACK

    override fun encodeBody(out: ByteBuf) {
        out.writeBoolean(false) // Packs required
        out.writeVarUInt(0) // Resource packs
        out.writeString(baseGameVersion)
        // Experiments count as a plain little-endian uint32 here, not a varint.
        out.writeIntLE(experiments.size)
        experiments.forEach { (name, enabled) ->
            out.writeString(name)
            out.writeBoolean(enabled)
        }
        out.writeBoolean(experiments.isNotEmpty()) // Experiments ever toggled
        out.writeBoolean(false) // Include editor packs
    }
}

/**
 * The items in the world, with the runtime IDs the protocol refers to them
 * by. The client waits for this after `StartGame`. Kore has no item data
 * yet, so the list is empty.
 */
data object ItemRegistry : ServerPacket {
    override val id get() = ITEM_REGISTRY

    override fun encodeBody(out: ByteBuf) {
        out.writeVarUInt(0)
    }
}

/** The view distance the server settled on, in chunks. */
data class ChunkRadiusUpdated(val radius: Int) : ServerPacket {
    override val id get() = CHUNK_RADIUS_UPDATED

    override fun encodeBody(out: ByteBuf) {
        out.writeVarInt(radius)
    }
}

fun decodeClientPacket(packet: ByteArray): ClientPacket {
    val buffer = Unpooled.wrappedBuffer(packet)
    return try {
        when (val id = buffer.readVarUInt() and PACKET_ID_MASK) {
            REQUEST_NETWORK_SETTINGS -> RequestNetworkSettings(protocol = buffer.readInt())
            LOGIN -> Login(protocol = buffer.readInt(), connectionRequest = buffer.readByteArray())
            CLIENT_CACHE_STATUS -> ClientCacheStatus(supported = buffer.readBoolean())
            REQUEST_CHUNK_RADIUS -> RequestChunkRadius(radius = buffer.readVarInt(), maxRadius = buffer.readUnsignedByte().toInt())
            RESOURCE_PACK_CLIENT_RESPONSE -> decodeResourcePackClientResponse(buffer)
            else -> UnhandledPacket(id, ByteBufUtil.getBytes(buffer))
        }
    } catch (e: IndexOutOfBoundsException) {
        throw PacketException("packet ends early")
    }
}

private fun decodeResourcePackClientResponse(buffer: ByteBuf): ResourcePackClientResponse {
    val tag = buffer.readVarUInt()
    val response = ResourcePackClientResponse.Response.entries.getOrNull(tag) ?: throw PacketException("unknown resource pack response $tag")
    buffer.readString() // The response's name, which only repeats the tag
    val packs = if (response == ResourcePackClientResponse.Response.DOWNLOADING) buffer.readList { readString() } else emptyList()
    return ResourcePackClientResponse(response, packs)
}

fun ServerPacket.encode(): ByteArray {
    val buffer = Unpooled.buffer()
    buffer.writeVarUInt(id)
    encodeBody(buffer)
    return ByteBufUtil.getBytes(buffer)
}
