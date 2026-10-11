package org.koremc.network.rtc

import java.net.Socket
import java.nio.ByteBuffer
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.X509ExtendedTrustManager

/** Guards against a misbehaving engine spinning the event loop. */
private const val MAX_STEPS = 64

private val EMPTY: ByteBuffer = ByteBuffer.allocate(0)

/** Creates server-side DTLS engines that present [certificate]. */
class DtlsContext(private val certificate: DtlsCertificate) {
    /**
     * An engine for one client. WebRTC has no certificate authority: the
     * client's certificate is trusted only if it matches one of the
     * [expected] fingerprints from its offer.
     */
    fun newEngine(expected: List<Fingerprint>): SSLEngine {
        val context = SSLContext.getInstance("DTLSv1.2").apply {
            init(certificate.keyManagers, arrayOf(FingerprintTrustManager(expected)), null)
        }
        // The engine's maximum packet size caps incoming records too, and clients fill whole
        // SCTP packets (about 1225 bytes once encrypted), so it stays at the JDK default.
        // Kore's own records stay small without it: SCTP packets are capped at its MTU, and
        // the handshake flight with a P-256 certificate is well under 1200 bytes.
        return context.createSSLEngine().apply {
            useClientMode = false
            needClientAuth = true
        }
    }
}

/**
 * Drives one DTLS association over a shared UDP socket. The engine does the
 * protocol work; this feeds it datagrams and passes along whatever it
 * produces. Retransmission is left to the client, which resends its last
 * flight on timeout and makes the engine resend Kore's.
 */
class DtlsTransport(private val engine: SSLEngine, private val transmit: (ByteBuffer) -> Unit) {
    var isConnected = false
        private set
    var isClosed = false
        private set

    val cipherSuite: String get() = engine.session.cipherSuite

    init {
        engine.beginHandshake()
        driveHandshake()
    }

    /** Feeds one datagram to the engine and returns the application data it carried. */
    fun receive(datagram: ByteBuffer): List<ByteBuffer> {
        val received = mutableListOf<ByteBuffer>()
        var input = datagram
        repeat(MAX_STEPS) {
            val output = ByteBuffer.allocate(engine.session.applicationBufferSize)
            val result = engine.unwrap(input, output)
            if (result.bytesProduced() > 0) received += output.flip()
            if (result.handshakeStatus == HandshakeStatus.FINISHED) isConnected = true
            if (result.status == Status.CLOSED) isClosed = true
            if (result.status != Status.OK) return received

            driveHandshake()

            // A datagram can hold several records. The engine buffers the rest and asks to be unwrapped again.
            when {
                engine.handshakeStatus == HandshakeStatus.NEED_UNWRAP_AGAIN -> input = EMPTY
                !input.hasRemaining() -> return received
            }
        }
        return received
    }

    /** Encrypts [data] into a single record and sends it. The receiver treats each record as one SCTP packet. */
    fun write(data: ByteBuffer) {
        val output = ByteBuffer.allocate(engine.session.packetBufferSize)
        val result = engine.wrap(data, output)
        if (result.bytesProduced() > 0) transmit(output.flip())
        if (result.status == Status.CLOSED) isClosed = true
        check(!data.hasRemaining() || isClosed) { "${data.remaining()} bytes didn't fit in one DTLS record" }
    }

    private fun driveHandshake() {
        repeat(MAX_STEPS) {
            when (engine.handshakeStatus) {
                HandshakeStatus.NEED_TASK -> generateSequence { engine.delegatedTask }.forEach(Runnable::run)
                HandshakeStatus.NEED_WRAP -> {
                    val output = ByteBuffer.allocate(engine.session.packetBufferSize)
                    val result = engine.wrap(EMPTY, output)
                    if (result.bytesProduced() > 0) transmit(output.flip())
                    if (result.handshakeStatus == HandshakeStatus.FINISHED) isConnected = true
                    if (result.status == Status.CLOSED) {
                        isClosed = true
                        return
                    }
                }
                else -> return
            }
        }
    }
}

private class FingerprintTrustManager(private val expected: List<Fingerprint>) : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = verify(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) = verify(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) = verify(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = refuse()
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) = refuse()
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) = refuse()

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun verify(chain: Array<out X509Certificate>) {
        val certificate = chain.firstOrNull() ?: throw CertificateException("Client sent no certificate")
        if (expected.none { it.matches(certificate) }) {
            throw CertificateException("Client certificate doesn't match the fingerprint in its offer")
        }
    }

    private fun refuse(): Unit = throw CertificateException("Kore only acts as the DTLS server")
}
