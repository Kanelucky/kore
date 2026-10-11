package org.koremc.network.rtc

import org.koremc.network.sctp.DataChannels
import java.net.InetSocketAddress
import java.security.interfaces.ECPublicKey
import java.util.concurrent.ConcurrentHashMap

/**
 * A client that has finished signaling. It's created when Kore answers the
 * client's offer, then found by ICE ufrag when the client's checks arrive over UDP.
 */
class Peer(
    val networkId: String,
    val localIce: IceCredentials,
    val remoteUfrag: String,
    val remoteFingerprints: List<Fingerprint>,
    /** The largest data channel message the client accepts. */
    val maxMessageSize: Int,
    /** The key the client proved in its offer, which its login must carry too. Null if it sent no identity. */
    val clientKey: ECPublicKey? = null,
) {
    // Set by the signaling thread before the peer is published, then only touched on the UDP event loop.
    var lastSeen: Long = System.nanoTime()
    val validatedAddresses = HashSet<InetSocketAddress>()
    /** Where replies go: the validated path the client is currently using. */
    var address: InetSocketAddress? = null
    var dtls: DtlsTransport? = null
    var channels: DataChannels? = null
}

/** Every known peer, keyed by the ICE ufrag Kore gave it in the answer. */
class Peers {
    private val byUfrag = ConcurrentHashMap<String, Peer>()

    fun add(peer: Peer) {
        byUfrag[peer.localIce.ufrag] = peer
    }

    operator fun get(ufrag: String): Peer? = byUfrag[ufrag]

    fun remove(peer: Peer) {
        byUfrag.remove(peer.localIce.ufrag, peer)
    }

    /** Removes and returns every peer last seen before [cutoff], a [System.nanoTime] reading. */
    fun removeIdle(cutoff: Long): List<Peer> =
        byUfrag.values.filter { it.lastSeen - cutoff < 0 }.onEach(::remove)
}
