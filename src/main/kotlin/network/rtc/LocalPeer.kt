package org.koremc.network.rtc

import org.koremc.network.sctp.SCTP_PORT
import kotlin.random.Random

/** The largest data channel message Kore accepts, matching the client's own limit. */
private const val MAX_MESSAGE_SIZE = 262_144L

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
