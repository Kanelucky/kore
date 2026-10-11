package org.koremc.network.rtc

import org.koremc.network.sctp.SCTP_PORT
import kotlin.math.min
import kotlin.random.Random

/** The largest data channel message Kore accepts, matching the client's own limit. */
private const val MAX_MESSAGE_SIZE = 262_144L

/** RFC 8841 §6: the size to assume when an offer doesn't give one. */
private const val DEFAULT_MAX_MESSAGE_SIZE = 65_536L

/**
 * The largest data channel message Kore should send this client: what its
 * offer allows (zero means no limit), capped at Kore's own limit because
 * bigger messages gain nothing.
 */
val SdpOffer.maxSendableMessageSize: Int
    get() = when (val size = maxMessageSize) {
        null -> DEFAULT_MAX_MESSAGE_SIZE
        0L -> MAX_MESSAGE_SIZE
        else -> min(size, MAX_MESSAGE_SIZE)
    }.toInt()

/**
 * This server as a WebRTC peer: the parts of every SDP answer that stay
 * fixed for the life of the process.
 */
class LocalPeer(
    private val certificate: DtlsCertificate,
    private val identity: OperatorIdentity,
    private val candidates: List<IceCandidate>,
) {
    fun answer(offer: SdpOffer): SdpAnswer {
        // Kore is always the DTLS server, so it never initiates anything: ICE-lite answers
        // the client's checks, and the client sends the first DTLS flight.
        if (offer.setup == "passive") throw InvalidOfferException("client wants the DTLS server role, which Kore always takes")

        return SdpAnswer(
            sessionId = Random.nextLong(0, Long.MAX_VALUE),
            mid = offer.mid,
            ice = IceCredentials.random(),
            fingerprint = certificate.fingerprint,
            identity = identity.assert(listOf(certificate.fingerprint)),
            setup = "passive",
            sctpPort = SCTP_PORT,
            maxMessageSize = MAX_MESSAGE_SIZE,
            candidates = candidates,
        )
    }
}
