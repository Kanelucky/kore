package org.koremc.network.rtc

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.CRC32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal const val STUN_BINDING_REQUEST = 0x0001
internal const val STUN_BINDING_SUCCESS = 0x0101

internal const val STUN_USERNAME = 0x0006
internal const val STUN_MESSAGE_INTEGRITY = 0x0008
internal const val STUN_XOR_MAPPED_ADDRESS = 0x0020
internal const val STUN_USE_CANDIDATE = 0x0025
internal const val STUN_FINGERPRINT = 0x8028

private const val HEADER_SIZE = 20
private const val INTEGRITY_SIZE = 24
private const val FINGERPRINT_SIZE = 8
private const val MAGIC_COOKIE = 0x2112A442
private const val FINGERPRINT_XOR = 0x5354554E

/**
 * A STUN message as ICE uses it (RFC 8489, RFC 8445). Only attributes ahead
 * of MESSAGE-INTEGRITY are kept, since anything after it is unauthenticated.
 */
class StunMessage internal constructor(
    val type: Int,
    val transactionId: ByteArray,
    val attributes: Map<Int, ByteArray>,
    private val raw: ByteArray,
    private val integrityOffset: Int,
) {
    val isBindingRequest get() = type == STUN_BINDING_REQUEST
    val username get() = attributes[STUN_USERNAME]?.decodeToString()
    val useCandidate get() = STUN_USE_CANDIDATE in attributes

    /** Checks MESSAGE-INTEGRITY. ICE keys it with the receiving side's password, so for checks Kore receives that's its own. */
    fun isAuthentic(password: String): Boolean {
        if (integrityOffset < 0) return false
        val signed = raw.copyOf(integrityOffset).withLength(integrityOffset + INTEGRITY_SIZE - HEADER_SIZE)
        return MessageDigest.isEqual(hmac(password, signed), raw.copyOfRange(integrityOffset + 4, integrityOffset + INTEGRITY_SIZE))
    }
}

/**
 * Parses a STUN message, or returns null if it's malformed. ICE puts
 * FINGERPRINT on every check, so messages without a valid one are rejected
 * too: that's what separates them from other traffic on the same port.
 */
fun parseStun(message: ByteArray): StunMessage? {
    if (message.size < HEADER_SIZE) return null
    val buffer = ByteBuffer.wrap(message)
    if (buffer.u16(2) != message.size - HEADER_SIZE || buffer.getInt(4) != MAGIC_COOKIE) return null

    val attributes = mutableMapOf<Int, ByteArray>()
    var integrityOffset = -1
    var offset = HEADER_SIZE
    while (offset + 4 <= message.size) {
        val type = buffer.u16(offset)
        val length = buffer.u16(offset + 2)
        val value = offset + 4
        if (value + length > message.size) return null

        when {
            type == STUN_FINGERPRINT -> {
                val valid = length == 4 && value + 4 == message.size && buffer.getInt(value) == fingerprint(message, offset)
                return if (valid) StunMessage(buffer.u16(0), message.copyOfRange(8, 20), attributes, message, integrityOffset) else null
            }
            integrityOffset >= 0 -> Unit
            type == STUN_MESSAGE_INTEGRITY -> if (length == 20) integrityOffset = offset else return null
            else -> attributes[type] = message.copyOfRange(value, value + length)
        }
        offset = value + padded(length)
    }
    return null
}

/** Answers a Binding request, telling the client the address it came from. */
fun bindingSuccess(request: StunMessage, sender: InetSocketAddress, password: String): ByteArray =
    encodeStun(
        type = STUN_BINDING_SUCCESS,
        transactionId = request.transactionId,
        attributes = listOf(STUN_XOR_MAPPED_ADDRESS to xorAddress(sender, request.transactionId)),
        password = password,
    )

/** Encodes a STUN message and appends MESSAGE-INTEGRITY (keyed with [password]) and FINGERPRINT. */
internal fun encodeStun(type: Int, transactionId: ByteArray, attributes: List<Pair<Int, ByteArray>>, password: String): ByteArray {
    val size = HEADER_SIZE + attributes.sumOf { 4 + padded(it.second.size) } + INTEGRITY_SIZE + FINGERPRINT_SIZE
    val buffer = ByteBuffer.allocate(size)
    buffer.putShort(type.toShort()).putShort(0).putInt(MAGIC_COOKIE).put(transactionId)
    for ((attribute, value) in attributes) {
        buffer.putShort(attribute.toShort()).putShort(value.size.toShort()).put(value)
        buffer.position(buffer.position() + padded(value.size) - value.size)
    }

    // Each trailer covers everything before it, with the length field already counting the trailer.
    buffer.putShort(2, (buffer.position() + INTEGRITY_SIZE - HEADER_SIZE).toShort())
    val integrity = hmac(password, buffer.array().copyOf(buffer.position()))
    buffer.putShort(STUN_MESSAGE_INTEGRITY.toShort()).putShort(20).put(integrity)

    buffer.putShort(2, (buffer.position() + FINGERPRINT_SIZE - HEADER_SIZE).toShort())
    val crc = fingerprint(buffer.array(), buffer.position())
    buffer.putShort(STUN_FINGERPRINT.toShort()).putShort(4).putInt(crc)

    return buffer.array()
}

private fun xorAddress(address: InetSocketAddress, transactionId: ByteArray): ByteArray {
    val ip = address.address.address
    val key = ByteBuffer.allocate(16).putInt(MAGIC_COOKIE).put(transactionId).array()
    return ByteBuffer.allocate(4 + ip.size)
        .put(0)
        .put((if (ip.size == 4) 0x01 else 0x02).toByte())
        .putShort((address.port xor (MAGIC_COOKIE ushr 16)).toShort())
        .put(ByteArray(ip.size) { (ip[it].toInt() xor key[it].toInt()).toByte() })
        .array()
}

private fun fingerprint(message: ByteArray, end: Int): Int =
    CRC32().apply { update(message, 0, end) }.value.toInt() xor FINGERPRINT_XOR

private fun hmac(password: String, data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA1").run {
        init(SecretKeySpec(password.toByteArray(), "HmacSHA1"))
        doFinal(data)
    }

private fun ByteArray.withLength(length: Int) = also { ByteBuffer.wrap(it).putShort(2, length.toShort()) }

private fun ByteBuffer.u16(index: Int) = getShort(index).toInt() and 0xFFFF

private fun padded(length: Int) = (length + 3) and 3.inv()
