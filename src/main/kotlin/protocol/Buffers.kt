package org.koremc.protocol

import io.netty.buffer.ByteBuf
import java.util.UUID

/** Thrown when a client sends bytes that don't decode as what they claim to be. */
class PacketException(message: String) : Exception(message)

/** An unsigned LEB128 varint, the length and ID prefix used throughout the Bedrock protocol. */
fun ByteBuf.readVarUInt(): Int {
    var value = 0
    for (shift in 0 until 35 step 7) {
        val byte = readByte().toInt()
        value = value or ((byte and 0x7F) shl shift)
        if (byte and 0x80 == 0) return value
    }
    throw PacketException("varuint32 runs past five bytes")
}

fun ByteBuf.writeVarUInt(value: Int): ByteBuf {
    var remaining = value
    while (remaining and 0x7F.inv() != 0) {
        writeByte((remaining and 0x7F) or 0x80)
        remaining = remaining ushr 7
    }
    return writeByte(remaining)
}

fun ByteBuf.writeVarULong(value: Long): ByteBuf {
    var remaining = value
    while (remaining and 0x7FL.inv() != 0L) {
        writeByte(((remaining and 0x7F) or 0x80).toInt())
        remaining = remaining ushr 7
    }
    return writeByte(remaining.toInt())
}

fun ByteBuf.writeVarLong(value: Long): ByteBuf = writeVarULong((value shl 1) xor (value shr 63))

/** A signed varint, zigzag-encoded so small negative numbers stay short. */
fun ByteBuf.readVarInt(): Int = readVarUInt().let { (it ushr 1) xor -(it and 1) }

fun ByteBuf.writeVarInt(value: Int): ByteBuf = writeVarUInt((value shl 1) xor (value shr 31))

fun ByteBuf.readByteArray(): ByteArray = ByteArray(readLength(readVarUInt())).also { readBytes(it) }

fun ByteBuf.writeByteArray(bytes: ByteArray): ByteBuf = writeVarUInt(bytes.size).writeBytes(bytes)

fun ByteBuf.readString(): String = readByteArray().decodeToString()

fun ByteBuf.writeString(value: String): ByteBuf = writeByteArray(value.encodeToByteArray())

/** A UUID as two little-endian 64-bit halves, most significant first. */
fun ByteBuf.writeUuid(uuid: UUID): ByteBuf = writeLongLE(uuid.mostSignificantBits).writeLongLE(uuid.leastSignificantBits)

/** A varuint32 count followed by that many items, refusing counts the remaining bytes couldn't possibly hold. */
fun <T> ByteBuf.readList(readItem: ByteBuf.() -> T): List<T> {
    val count = readVarUInt()
    if (count < 0 || count > readableBytes()) throw PacketException("list of $count items can't fit in ${readableBytes()} bytes")
    return List(count) { readItem() }
}

/** A UTF-8 string behind a little-endian int32 length, as the login connection request uses. */
fun ByteBuf.readIntLEString(): String = ByteArray(readLength(readIntLE())).also { readBytes(it) }.decodeToString()

private fun ByteBuf.readLength(length: Int): Int {
    if (length < 0 || length > readableBytes()) throw PacketException("length $length runs past the ${readableBytes()} bytes left")
    return length
}
