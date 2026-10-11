package org.koremc.network.sctp

import java.nio.ByteBuffer
import java.util.zip.CRC32C
import kotlin.math.sign

/** The SCTP port both ends use inside DTLS. WebRTC always uses 5000 (RFC 8841). */
const val SCTP_PORT = 5000

internal const val COMMON_HEADER_SIZE = 12
internal const val DATA_HEADER_SIZE = 16

private const val DATA = 0
private const val INIT = 1
private const val INIT_ACK = 2
private const val SACK = 3
private const val HEARTBEAT = 4
private const val HEARTBEAT_ACK = 5
private const val ABORT = 6
private const val SHUTDOWN = 7
private const val SHUTDOWN_ACK = 8
private const val COOKIE_ECHO = 10
private const val COOKIE_ACK = 11
private const val SHUTDOWN_COMPLETE = 14
internal const val FORWARD_TSN = 192

private const val FLAG_UNORDERED = 0x04
private const val FLAG_BEGINNING = 0x02
private const val FLAG_ENDING = 0x01

/** A transmission sequence number. TSNs wrap at 2^32, so they compare with serial number arithmetic (RFC 1982). */
@JvmInline
value class Tsn(val raw: Int) : Comparable<Tsn> {
    operator fun plus(count: Int) = Tsn(raw + count)
    operator fun minus(count: Int) = Tsn(raw - count)
    operator fun minus(other: Tsn) = raw - other.raw
    override fun compareTo(other: Tsn) = (raw - other.raw).sign
    override fun toString() = raw.toUInt().toString()
}

/** One SCTP packet (RFC 9260 §3): a common header and the chunks bundled behind it. */
class SctpPacket(val verificationTag: Int, val chunks: List<Chunk>)

/** The chunk types WebRTC data channels use: RFC 9260 plus FORWARD-TSN from RFC 3758. */
sealed interface Chunk

class DataChunk(
    val tsn: Tsn,
    val streamId: Int,
    val ssn: Int,
    val ppid: Int,
    val payload: ByteArray,
    val unordered: Boolean = false,
    val beginning: Boolean = true,
    val ending: Boolean = true,
) : Chunk

/** INIT, or INIT ACK when [ack] is set. They share a layout; INIT ACK carries the state cookie as a parameter. */
class Init(
    val ack: Boolean,
    val initiateTag: Int,
    val window: Int,
    val outboundStreams: Int,
    val inboundStreams: Int,
    val initialTsn: Tsn,
    val parameters: List<Parameter> = emptyList(),
) : Chunk {
    fun parameter(type: Int): ByteArray? = parameters.firstOrNull { it.type == type }?.value
}

class Parameter(val type: Int, val value: ByteArray)

/** Selective acknowledgement. [gaps] are offsets from [cumulativeTsn], as on the wire. */
class Sack(val cumulativeTsn: Tsn, val window: Int, val gaps: List<IntRange>, val duplicates: List<Tsn>) : Chunk

/** HEARTBEAT, or HEARTBEAT ACK when [ack] is set. The acknowledgement echoes [info] back unchanged. */
class Heartbeat(val ack: Boolean, val info: ByteArray) : Chunk

class Abort(val causes: ByteArray) : Chunk

class Shutdown(val cumulativeTsn: Tsn) : Chunk

data object ShutdownAck : Chunk

data object ShutdownComplete : Chunk

class CookieEcho(val cookie: ByteArray) : Chunk

data object CookieAck : Chunk

/** Tells the receiver to stop waiting for abandoned TSNs. [streams] pairs each ordered stream with its last skipped SSN. */
class ForwardTsn(val newCumulativeTsn: Tsn, val streams: List<Pair<Int, Int>>) : Chunk

/** ERROR, and any chunk type Kore doesn't use. */
class OtherChunk(val type: Int, val flags: Int, val value: ByteArray) : Chunk

/** Parses a packet, or returns null if it's malformed or fails its CRC32c check. */
fun parsePacket(packet: ByteBuffer): SctpPacket? {
    val bytes = ByteArray(packet.remaining()).also { packet.get(it) }
    if (bytes.size < COMMON_HEADER_SIZE) return null

    val buffer = ByteBuffer.wrap(bytes)
    val checksum = buffer.getInt(8)
    buffer.putInt(8, 0)
    if (crc32c(bytes) != checksum) return null

    val chunks = mutableListOf<Chunk>()
    var offset = COMMON_HEADER_SIZE
    while (offset + 4 <= bytes.size) {
        val length = buffer.u16(offset + 2)
        if (length < 4 || offset + length > bytes.size) return null
        val value = ByteBuffer.wrap(bytes, offset + 4, length - 4).slice()
        chunks += parseChunk(bytes[offset].toInt() and 0xFF, bytes[offset + 1].toInt() and 0xFF, value) ?: return null
        offset += padded(length)
    }
    return SctpPacket(buffer.getInt(4), chunks)
}

fun SctpPacket.encode(): ByteBuffer {
    val buffer = ByteBuffer.allocate(COMMON_HEADER_SIZE + chunks.sumOf { padded(it.encodedLength()) })
    buffer.putShort(SCTP_PORT.toShort()).putShort(SCTP_PORT.toShort()).putInt(verificationTag).putInt(0)
    chunks.forEach { buffer.putChunk(it) }
    buffer.putInt(8, crc32c(buffer.array()))
    return buffer.flip()
}

/** A chunk's encoded length, excluding the padding that follows it. */
internal fun Chunk.encodedLength(): Int = 4 + when (this) {
    is DataChunk -> DATA_HEADER_SIZE - 4 + payload.size
    is Init -> 16 + parametersLength(parameters)
    is Sack -> 12 + 4 * gaps.size + 4 * duplicates.size
    is Heartbeat -> info.size
    is Abort -> causes.size
    is Shutdown -> 4
    is CookieEcho -> cookie.size
    is ForwardTsn -> 4 + 4 * streams.size
    is OtherChunk -> value.size
    ShutdownAck, ShutdownComplete, CookieAck -> 0
}

/** Every parameter is padded to four bytes, but the chunk length leaves out the last one's padding. */
private fun parametersLength(parameters: List<Parameter>): Int {
    val total = parameters.sumOf { padded(4 + it.value.size) }
    val trailing = parameters.lastOrNull()?.let { padded(4 + it.value.size) - (4 + it.value.size) } ?: 0
    return total - trailing
}

private fun parseChunk(type: Int, flags: Int, value: ByteBuffer): Chunk? {
    val size = value.remaining()
    return when (type) {
        DATA -> if (size <= 12) null else DataChunk(
            tsn = Tsn(value.getInt(0)),
            streamId = value.u16(4),
            ssn = value.u16(6),
            ppid = value.getInt(8),
            payload = value.bytesFrom(12),
            unordered = flags and FLAG_UNORDERED != 0,
            beginning = flags and FLAG_BEGINNING != 0,
            ending = flags and FLAG_ENDING != 0,
        )
        INIT, INIT_ACK -> if (size < 16) null else Init(
            ack = type == INIT_ACK,
            initiateTag = value.getInt(0),
            window = value.getInt(4),
            outboundStreams = value.u16(8),
            inboundStreams = value.u16(10),
            initialTsn = Tsn(value.getInt(12)),
            parameters = parseParameters(value, 16) ?: return null,
        )
        SACK -> {
            if (size < 12) return null
            val gapCount = value.u16(8)
            val duplicateCount = value.u16(10)
            if (size < 12 + 4 * (gapCount + duplicateCount)) return null
            Sack(
                cumulativeTsn = Tsn(value.getInt(0)),
                window = value.getInt(4),
                gaps = List(gapCount) { value.u16(12 + 4 * it)..value.u16(14 + 4 * it) },
                duplicates = List(duplicateCount) { Tsn(value.getInt(12 + 4 * gapCount + 4 * it)) },
            )
        }
        HEARTBEAT, HEARTBEAT_ACK -> Heartbeat(type == HEARTBEAT_ACK, value.bytesFrom(0))
        ABORT -> Abort(value.bytesFrom(0))
        SHUTDOWN -> if (size < 4) null else Shutdown(Tsn(value.getInt(0)))
        SHUTDOWN_ACK -> ShutdownAck
        SHUTDOWN_COMPLETE -> ShutdownComplete
        COOKIE_ECHO -> CookieEcho(value.bytesFrom(0))
        COOKIE_ACK -> CookieAck
        FORWARD_TSN -> if (size < 4) null else ForwardTsn(
            newCumulativeTsn = Tsn(value.getInt(0)),
            streams = List((size - 4) / 4) { value.u16(4 + 4 * it) to value.u16(6 + 4 * it) },
        )
        else -> OtherChunk(type, flags, value.bytesFrom(0))
    }
}

private fun parseParameters(value: ByteBuffer, start: Int): List<Parameter>? {
    val parameters = mutableListOf<Parameter>()
    var offset = start
    while (offset + 4 <= value.remaining()) {
        val length = value.u16(offset + 2)
        if (length < 4 || offset + length > value.remaining()) return null
        parameters += Parameter(value.u16(offset), ByteArray(length - 4).also { value.get(offset + 4, it) })
        offset += padded(length)
    }
    return parameters
}

private fun ByteBuffer.putChunk(chunk: Chunk) {
    val start = position()
    val (type, flags) = when (chunk) {
        is DataChunk -> DATA to ((if (chunk.unordered) FLAG_UNORDERED else 0) or
            (if (chunk.beginning) FLAG_BEGINNING else 0) or
            (if (chunk.ending) FLAG_ENDING else 0))
        is Init -> (if (chunk.ack) INIT_ACK else INIT) to 0
        is Sack -> SACK to 0
        is Heartbeat -> (if (chunk.ack) HEARTBEAT_ACK else HEARTBEAT) to 0
        is Abort -> ABORT to 0
        is Shutdown -> SHUTDOWN to 0
        is CookieEcho -> COOKIE_ECHO to 0
        is ForwardTsn -> FORWARD_TSN to 0
        is OtherChunk -> chunk.type to chunk.flags
        ShutdownAck -> SHUTDOWN_ACK to 0
        ShutdownComplete -> SHUTDOWN_COMPLETE to 0
        CookieAck -> COOKIE_ACK to 0
    }
    put(type.toByte()).put(flags.toByte()).putShort(chunk.encodedLength().toShort())

    when (chunk) {
        is DataChunk -> putInt(chunk.tsn.raw).putShort(chunk.streamId.toShort()).putShort(chunk.ssn.toShort())
            .putInt(chunk.ppid).put(chunk.payload)
        is Init -> {
            putInt(chunk.initiateTag).putInt(chunk.window)
                .putShort(chunk.outboundStreams.toShort()).putShort(chunk.inboundStreams.toShort())
                .putInt(chunk.initialTsn.raw)
            for (parameter in chunk.parameters) {
                val parameterStart = position()
                putShort(parameter.type.toShort()).putShort((4 + parameter.value.size).toShort()).put(parameter.value)
                position(parameterStart + padded(4 + parameter.value.size))
            }
        }
        is Sack -> {
            putInt(chunk.cumulativeTsn.raw).putInt(chunk.window)
                .putShort(chunk.gaps.size.toShort()).putShort(chunk.duplicates.size.toShort())
            chunk.gaps.forEach { putShort(it.first.toShort()).putShort(it.last.toShort()) }
            chunk.duplicates.forEach { putInt(it.raw) }
        }
        is Heartbeat -> put(chunk.info)
        is Abort -> put(chunk.causes)
        is Shutdown -> putInt(chunk.cumulativeTsn.raw)
        is CookieEcho -> put(chunk.cookie)
        is ForwardTsn -> {
            putInt(chunk.newCumulativeTsn.raw)
            chunk.streams.forEach { (stream, ssn) -> putShort(stream.toShort()).putShort(ssn.toShort()) }
        }
        is OtherChunk -> put(chunk.value)
        ShutdownAck, ShutdownComplete, CookieAck -> Unit
    }
    position(start + padded(chunk.encodedLength()))
}

/**
 * SCTP's CRC32c goes on the wire least significant byte first, unlike every
 * other field. Returning it byte-swapped lets callers read and write it as a
 * normal big-endian int.
 */
private fun crc32c(packet: ByteArray) = Integer.reverseBytes(CRC32C().apply { update(packet) }.value.toInt())

private fun ByteBuffer.u16(index: Int) = getShort(index).toInt() and 0xFFFF

private fun ByteBuffer.bytesFrom(index: Int) = ByteArray(remaining() - index).also { get(index, it) }

internal fun padded(length: Int) = (length + 3) and 3.inv()
