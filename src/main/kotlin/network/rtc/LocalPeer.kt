package org.koremc.network.rtc

import kotlin.random.Random

/** The SCTP port inside the DTLS association. Every WebRTC stack uses 5000. */
private const val SCTP_PORT = 5000

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
    fun answer(offer: SdpOffer) = SdpAnswer(
        sessionId = Random.nextLong(0, Long.MAX_VALUE),
        mid = offer.mid,
        ice = IceCredentials.random(),
        fingerprint = certificate.fingerprint,
        identity = identity.assert(listOf(certificate.fingerprint)),
        // Taking the DTLS server role whenever the client allows it means Kore never initiates
        // anything: ICE-lite answers the client's checks, and the client sends the first DTLS flight.
        setup = if (offer.setup == "passive") "active" else "passive",
        sctpPort = SCTP_PORT,
        maxMessageSize = MAX_MESSAGE_SIZE,
        candidates = candidates,
    )
}
