package org.koremc.network.rtc

private val DTLS_ROLES = setOf("actpass", "active", "passive")

/** Thrown when a client's SDP offer is malformed or isn't a NetherNet data channel offer. */
class InvalidOfferException(message: String) : Exception(message)

/** A DTLS certificate fingerprint, as carried by an `a=fingerprint` line. */
data class Fingerprint(val algorithm: String, val digest: String) {
    override fun toString() = "$algorithm $digest"

    companion object {
        fun parse(value: String): Fingerprint {
            val parts = value.trim().split(' ')
            if (parts.size != 2) invalid("malformed fingerprint '$value'")
            return Fingerprint(parts[0], parts[1])
        }
    }
}

/** The parts of a client's offer that answering it depends on. */
data class SdpOffer(
    val mid: String,
    val iceUfrag: String,
    val icePwd: String,
    val fingerprints: List<Fingerprint>,
    val setup: String,
    val sctpPort: Int,
    val maxMessageSize: Long?,
    val candidates: List<String>,
    /** Raw `a=identity` value. Optional for clients; admitting players without one is a server policy decision. */
    val identity: String?,
)

/**
 * Parses a client offer. NetherNet offers carry exactly one bundled
 * `m=application` section for SCTP data channels, with every ICE candidate
 * included up front because trickle ICE is disabled.
 */
fun parseOffer(sdp: String): SdpOffer {
    val lines = sdp.lines().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
    val mediaStart = lines.indexOfFirst { it.startsWith("m=") }
    if (mediaStart < 0) invalid("no media section")

    val media = lines.subList(mediaStart, lines.size)
    if (media.count { it.startsWith("m=") } != 1) invalid("expected exactly one media section")

    val mediaLine = media.first().removePrefix("m=").split(' ')
    if (mediaLine.getOrNull(0) != "application" ||
        mediaLine.getOrNull(2) != "UDP/DTLS/SCTP" ||
        mediaLine.getOrNull(3) != "webrtc-datachannel"
    ) invalid("not a data channel offer: '${media.first()}'")

    // Media-level attributes take precedence over session-level ones.
    val attributes = attributes(lines.subList(0, mediaStart)) + attributes(media)
    fun first(name: String) = attributes[name]?.first() ?: invalid("missing a=$name")

    return SdpOffer(
        mid = first("mid"),
        iceUfrag = first("ice-ufrag"),
        icePwd = first("ice-pwd"),
        fingerprints = attributes["fingerprint"].orEmpty().map(Fingerprint::parse).ifEmpty { invalid("missing a=fingerprint") },
        setup = first("setup").also { if (it !in DTLS_ROLES) invalid("unsupported a=setup:$it") },
        sctpPort = first("sctp-port").toIntOrNull() ?: invalid("malformed a=sctp-port"),
        maxMessageSize = attributes["max-message-size"]?.first()?.toLongOrNull(),
        candidates = attributes["candidate"].orEmpty(),
        identity = attributes["identity"]?.first(),
    )
}

/** The server's answer. [encode] produces the `application/sdp` body sent back to the client. */
data class SdpAnswer(
    val sessionId: Long,
    val mid: String,
    val ice: IceCredentials,
    val fingerprint: Fingerprint,
    val identity: String,
    val setup: String,
    val sctpPort: Int,
    val maxMessageSize: Long,
    val candidates: List<IceCandidate>,
) {
    // The client signs and verifies a=identity against the a=fingerprint lines before it,
    // so both stay at session level, ahead of the m= line.
    fun encode(): String = buildString {
        line("v=0")
        line("o=- $sessionId 2 IN IP4 127.0.0.1")
        line("s=-")
        line("t=0 0")
        line("a=group:BUNDLE $mid")
        line("a=ice-lite")
        line("a=fingerprint:$fingerprint")
        line("a=identity:$identity")
        line("m=application 9 UDP/DTLS/SCTP webrtc-datachannel")
        line("c=IN IP4 0.0.0.0")
        line("a=ice-ufrag:${ice.ufrag}")
        line("a=ice-pwd:${ice.pwd}")
        line("a=setup:$setup")
        line("a=mid:$mid")
        line("a=sctp-port:$sctpPort")
        line("a=max-message-size:$maxMessageSize")
        candidates.forEach { line("a=candidate:$it") }
        line("a=end-of-candidates")
    }
}

private fun StringBuilder.line(value: String) = append(value).append("\r\n")

private fun attributes(lines: List<String>): Map<String, List<String>> =
    lines.filter { it.startsWith("a=") }
        .map { it.removePrefix("a=").split(':', limit = 2) }
        .groupBy(keySelector = { it[0] }, valueTransform = { it.getOrElse(1) { "" } })

private fun invalid(reason: String): Nothing = throw InvalidOfferException(reason)
