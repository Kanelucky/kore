package org.koremc.network.rtc

import java.net.Socket
import java.nio.ByteBuffer
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.X509ExtendedTrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DtlsTransportTest {
    private val serverCertificate = DtlsCertificate.generate()
    private val clientCertificate = DtlsCertificate.generate()

    @Test
    fun `handshake completes and carries application data`() {
        val link = Link(expected = listOf(clientCertificate.fingerprint))
        link.pump()

        assertTrue(link.server.isConnected)
        assertTrue(link.client.isConnected)

        link.client.write(ByteBuffer.wrap("sctp".toByteArray()))
        assertEquals(listOf("sctp"), link.pump().map { Charsets.UTF_8.decode(it).toString() })
    }

    @Test
    fun `client certificate must match the fingerprint in the offer`() {
        val link = Link(expected = listOf(serverCertificate.fingerprint))

        assertFailsWith<SSLException> { link.pump() }
        assertFalse(link.server.isConnected)
    }

    /** A client and a Kore server wired back to back, with datagrams queued between them. */
    private inner class Link(expected: List<Fingerprint>) {
        private val toServer = ArrayDeque<ByteBuffer>()
        private val toClient = ArrayDeque<ByteBuffer>()

        val server = DtlsTransport(DtlsContext(serverCertificate).newEngine(expected)) { toClient += it }
        val client = DtlsTransport(dtlsClientEngine(clientCertificate)) { toServer += it }

        /** Delivers queued datagrams until both sides go quiet, returning what the server received. */
        fun pump(): List<ByteBuffer> = buildList {
            repeat(32) {
                if (toServer.isEmpty() && toClient.isEmpty()) return@buildList
                while (toServer.isNotEmpty()) addAll(server.receive(toServer.removeFirst()))
                while (toClient.isNotEmpty()) client.receive(toClient.removeFirst())
            }
        }
    }
}

/** A JDK DTLS client standing in for the game's WebRTC stack. It trusts any server certificate. */
internal fun dtlsClientEngine(certificate: DtlsCertificate): SSLEngine {
    val trustAll = object : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    return SSLContext.getInstance("DTLSv1.2")
        .apply { init(certificate.keyManagers, arrayOf(trustAll), null) }
        .createSSLEngine()
        .apply {
            useClientMode = true
            sslParameters = sslParameters.apply { maximumPacketSize = 1200 }
        }
}
