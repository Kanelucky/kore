package org.koremc.network

import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.socket.DatagramPacket
import org.koremc.network.rtc.DtlsCertificate
import org.koremc.network.rtc.DtlsContext
import org.koremc.network.rtc.DtlsTransport
import org.koremc.network.rtc.IceCredentials
import org.koremc.network.rtc.Peer
import org.koremc.network.rtc.Peers
import org.koremc.network.rtc.STUN_BINDING_REQUEST
import org.koremc.network.rtc.STUN_BINDING_SUCCESS
import org.koremc.network.rtc.STUN_USERNAME
import org.koremc.network.rtc.STUN_USE_CANDIDATE
import org.koremc.network.rtc.dtlsClientEngine
import org.koremc.network.rtc.encodeStun
import org.koremc.network.rtc.parseStun
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetherNetRtcHandlerTest {
    private val server = InetSocketAddress("203.0.113.7", 19132)
    private val client = InetSocketAddress("198.51.100.4", 52000)

    private val serverCertificate = DtlsCertificate.generate()
    private val clientCertificate = DtlsCertificate.generate()

    private val peer = Peer(
        networkId = "9876543210123456789",
        localIce = IceCredentials("kore", "korekorekorekorekorekore"),
        remoteUfrag = "game",
        remoteFingerprints = listOf(clientCertificate.fingerprint),
    )
    private val peers = Peers().apply { add(peer) }
    private val channel = EmbeddedChannel(NetherNetRtcHandler(peers, DtlsContext(serverCertificate)))

    @Test
    fun `valid checks are answered and pick the client's address`() {
        channel.writeInbound(datagram(check(), from = client))

        val response = assertNotNull(channel.readOutbound<DatagramPacket>())
        val message = assertNotNull(parseStun(ByteBufUtil.getBytes(response.content())))
        response.release()

        assertEquals(client, response.recipient())
        assertEquals(STUN_BINDING_SUCCESS, message.type)
        assertTrue(message.isAuthentic(peer.localIce.pwd))
        assertEquals(client, peer.address)
    }

    @Test
    fun `checks with the wrong password are ignored`() {
        channel.writeInbound(datagram(check(password = "wrongwrongwrongwrongwrong"), from = client))

        assertNull(channel.readOutbound<DatagramPacket>())
        assertNull(peer.address)
    }

    @Test
    fun `checks for unknown ufrags are ignored`() {
        channel.writeInbound(datagram(check(username = "nobody:game"), from = client))

        assertNull(channel.readOutbound<DatagramPacket>())
    }

    @Test
    fun `dtls from an address ice never picked is ignored`() {
        channel.writeInbound(datagram(byteArrayOf(22, -2, -3, 0, 0), from = client))

        assertNull(channel.readOutbound<DatagramPacket>())
        assertNull(peer.dtls)
    }

    @Test
    fun `dtls connects once ice has picked a path`() {
        channel.writeInbound(datagram(check(), from = client))
        channel.readOutbound<DatagramPacket>().release()

        val toServer = ArrayDeque<ByteBuffer>()
        val dtlsClient = DtlsTransport(dtlsClientEngine(clientCertificate)) { toServer += it }
        repeat(32) {
            while (toServer.isNotEmpty()) channel.writeInbound(datagram(toServer.removeFirst().toBytes(), from = client))
            while (true) {
                val packet = channel.readOutbound<DatagramPacket>() ?: break
                assertEquals(client, packet.recipient())
                dtlsClient.receive(packet.content().nioBuffer())
                packet.release()
            }
        }

        assertTrue(dtlsClient.isConnected)
        assertTrue(assertNotNull(peer.dtls).isConnected)
    }

    private fun check(username: String = "kore:game", password: String = peer.localIce.pwd) = encodeStun(
        type = STUN_BINDING_REQUEST,
        transactionId = Random.nextBytes(12),
        attributes = listOf(STUN_USERNAME to username.toByteArray(), STUN_USE_CANDIDATE to ByteArray(0)),
        password = password,
    )

    private fun datagram(bytes: ByteArray, from: InetSocketAddress) =
        DatagramPacket(Unpooled.wrappedBuffer(bytes), server, from)

    private fun ByteBuffer.toBytes() = ByteArray(remaining()).also { get(it) }
}
