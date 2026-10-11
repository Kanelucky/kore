package org.koremc.protocol

import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** The compression a batch uses, with the ID NetworkSettings sends. Kore only ever asks for zlib. */
enum class CompressionAlgorithm(val id: Int) {
    ZLIB(0),
    SNAPPY(1),
    NONE(0xFFFF),
}

// The byte that leads each batch once compression is on.
private const val BATCH_ZLIB = 0x00
private const val BATCH_UNCOMPRESSED = 0xFF

/** The most a batch may inflate to, so a tiny compressed batch can't exhaust memory. */
private const val MAX_BATCH_SIZE = 16 shl 20

/**
 * Packs packets into batches and unpacks them again. Every NetherNet message
 * carries one batch: a run of packets, each behind a varuint32 length.
 *
 * Once NetworkSettings has gone out, every batch in both directions starts
 * with a byte naming its compression: 0x00 for raw DEFLATE, or 0xFF for a
 * batch under the threshold that went uncompressed. Unlike RakNet, NetherNet
 * has no 0xFE marker in front, and no encryption layer, since DTLS already
 * encrypts everything.
 */
class BatchCodec {
    private var compressionThreshold: Int? = null

    /** Turns on compression for every batch after this, in both directions. Batches of [threshold] bytes or more get compressed. */
    fun enableCompression(threshold: Int) {
        require(threshold > 0) { "threshold must be positive" }
        compressionThreshold = threshold
    }

    fun decode(message: ByteArray): List<ByteArray> {
        val payload = if (compressionThreshold == null) message else decompress(message)
        val batch = Unpooled.wrappedBuffer(payload)
        return try {
            buildList { while (batch.isReadable) add(batch.readByteArray()) }
        } catch (e: IndexOutOfBoundsException) {
            throw PacketException("batch ends partway through a packet")
        }
    }

    fun encode(packets: List<ByteArray>): ByteArray {
        val batch = Unpooled.buffer()
        packets.forEach { batch.writeByteArray(it) }
        val payload = ByteBufUtil.getBytes(batch)

        val threshold = compressionThreshold ?: return payload
        return if (payload.size >= threshold) byteArrayOf(BATCH_ZLIB.toByte()) + deflate(payload) else byteArrayOf(BATCH_UNCOMPRESSED.toByte()) + payload
    }

    private fun decompress(message: ByteArray): ByteArray {
        if (message.isEmpty()) throw PacketException("batch has no compression byte")
        return when (val algorithm = message[0].toInt() and 0xFF) {
            BATCH_UNCOMPRESSED -> message.copyOfRange(1, message.size)
            BATCH_ZLIB -> inflate(message)
            else -> throw PacketException("batch uses compression $algorithm, but Kore asked for zlib")
        }
    }
}

private fun deflate(data: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
    try {
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 2 + 64)
        val chunk = ByteArray(8192)
        while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
        return out.toByteArray()
    } finally {
        deflater.end()
    }
}

/** Inflates the raw DEFLATE stream after the compression byte. */
private fun inflate(message: ByteArray): ByteArray {
    val inflater = Inflater(true)
    try {
        inflater.setInput(message, 1, message.size - 1)
        val out = ByteArrayOutputStream(message.size * 4)
        val chunk = ByteArray(8192)
        while (!inflater.finished()) {
            val count = inflater.inflate(chunk)
            if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(chunk, 0, count)
            if (out.size() > MAX_BATCH_SIZE) throw PacketException("batch inflates past $MAX_BATCH_SIZE bytes")
        }
        return out.toByteArray()
    } catch (e: DataFormatException) {
        throw PacketException("batch isn't valid DEFLATE: ${e.message}")
    } finally {
        inflater.end()
    }
}
