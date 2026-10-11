package org.koremc.network.rtc

import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom

private const val ICE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

private val random = SecureRandom()

/** Per-session ICE credentials. The client signs its STUN checks with [pwd]. */
data class IceCredentials(val ufrag: String, val pwd: String) {
    companion object {
        // RFC 8839 asks for at least 24 bits of randomness in the ufrag and 128 in the password.
        fun random() = IceCredentials(ufrag = randomIceChars(8), pwd = randomIceChars(24))
    }
}

/** A host candidate. Kore runs ICE-lite, so these are the only addresses it ever offers. */
data class IceCandidate(val foundation: Int, val priority: Long, val address: InetAddress, val port: Int) {
    /** The value of an `a=candidate` line. */
    override fun toString() = "$foundation 1 udp $priority ${address.hostAddress.substringBefore('%')} $port typ host"
}

/**
 * One host candidate per address, all on the same UDP [port]. Earlier
 * addresses get higher priority, and IPv4 sorts first since that is what
 * most clients reach.
 */
fun hostCandidates(port: Int, addresses: List<InetAddress>): List<IceCandidate> =
    addresses.sortedBy { it is Inet6Address }.mapIndexed { index, address ->
        // RFC 8445 §5.1.2.1 with type preference 126 (host) and component 1.
        val priority = (126L shl 24) + ((65535L - index) shl 8) + 255
        IceCandidate(foundation = index + 1, priority = priority, address = address, port = port)
    }

/** Every routable address on this machine, skipping loopback and link-local. */
fun localAddresses(): List<InetAddress> =
    NetworkInterface.networkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses().toList() }
        .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress || it.isAnyLocalAddress }

private fun randomIceChars(length: Int) =
    String(CharArray(length) { ICE_CHARS[random.nextInt(ICE_CHARS.length)] })
