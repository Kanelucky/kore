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
import org.koremc.network.sctp.CookieAck
import org.koremc.network.sctp.CookieEcho
import org.koremc.network.sctp.DataChunk
import org.koremc.network.sctp.Init
import org.koremc.network.sctp.SctpPacket
import org.koremc.network.sctp.Tsn
import org.koremc.network.sctp.dcepOpen
import org.koremc.network.sctp.encode
import org.koremc.network.sctp.parsePacket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
        maxMessageSize = 262144,
    )
    private val peers = Peers().apply { add(peer) }
    private val messages = mutableListOf<Pair<String, ByteArray>>()
    private val channel = EmbeddedChannel(
        NetherNetRtcHandler(peers, DtlsContext(serverCertificate)) { connection, message -> messages += connection.networkId to message },
    )
    private val toServer = ArrayDeque<ByteBuffer>()

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
    fun `dtls from an address that never passed a check is ignored`() {
        channel.writeInbound(datagram(byteArrayOf(22, -2, -3, 0, 0), from = client))

        assertNull(channel.readOutbound<DatagramPacket>())
        assertNull(peer.dtls)
    }

    @Test
    fun `dtls connects once ice has picked a path`() {
        val dtlsClient = connect()

        assertTrue(dtlsClient.isConnected)
        assertTrue(assertNotNull(peer.dtls).isConnected)
    }

    @Test
    fun `replies follow the client to another validated path`() {
        val other = InetSocketAddress("198.51.100.4", 52001)
        val dtlsClient = connect()
        channel.writeInbound(datagram(check(useCandidate = false), from = other))
        channel.readOutbound<DatagramPacket>().release()
        assertEquals(client, peer.address)

        dtlsClient.write(ByteBuffer.wrap(byteArrayOf(1)))
        pump(dtlsClient, from = other)

        assertEquals(other, peer.address)
    }

    @Test
    fun `data channels open and deliver messages over the whole stack`() {
        val dtlsClient = connect()

        dtlsClient.write(SctpPacket(0, listOf(Init(false, 0x5EED, 1 shl 20, 1024, 1024, Tsn(1)))).encode())
        val initAck = pump(dtlsClient).flatMap { parse(it).chunks }.filterIsInstance<Init>().single()

        val open = DataChunk(Tsn(1), streamId = 0, ssn = 0, ppid = 50, payload = dcepOpen("ReliableDataChannel", 0x00))
        // Two NetherNet fragments: header 1 (one more to come), then header 0.
        val first = DataChunk(Tsn(2), streamId = 0, ssn = 1, ppid = 53, payload = byteArrayOf(1, 4))
        val last = DataChunk(Tsn(3), streamId = 0, ssn = 2, ppid = 53, payload = byteArrayOf(0, 2))
        dtlsClient.write(SctpPacket(initAck.initiateTag, listOf(CookieEcho(checkNotNull(initAck.parameter(7))), open, first, last)).encode())
        val replies = pump(dtlsClient).flatMap { parse(it).chunks }

        assertTrue(CookieAck in replies)
        assertContentEquals(byteArrayOf(0x02), replies.filterIsInstance<DataChunk>().single().payload)
        assertNotNull(peer.channels?.get("ReliableDataChannel"))
        assertEquals(listOf(peer.networkId), messages.map { it.first })
        assertContentEquals(byteArrayOf(4, 2), messages.single().second)
    }

    /** Passes ICE from [client], then runs the DTLS handshake. */
    private fun connect(): DtlsTransport {
        channel.writeInbound(datagram(check(), from = client))
        channel.readOutbound<DatagramPacket>().release()
        return DtlsTransport(dtlsClientEngine(clientCertificate)) { toServer += it }.also { pump(it) }
    }

    /** Moves datagrams both ways until both sides go quiet, returning the application data the client decrypted. */
    private fun pump(dtlsClient: DtlsTransport, from: InetSocketAddress = client): List<ByteBuffer> = buildList {
        repeat(32) {
            while (toServer.isNotEmpty()) channel.writeInbound(datagram(toServer.removeFirst().toBytes(), from))
            val outbound = generateSequence { channel.readOutbound<DatagramPacket>() }.toList()
            if (outbound.isEmpty()) return@buildList
            for (packet in outbound) {
                addAll(dtlsClient.receive(packet.content().nioBuffer()))
                packet.release()
            }
        }
    }

    private fun parse(packet: ByteBuffer) = checkNotNull(parsePacket(packet)) { "Kore sent a malformed SCTP packet" }

    private fun check(
        username: String = "kore:game",
        password: String = peer.localIce.pwd,
        useCandidate: Boolean = true,
    ) = encodeStun(
        type = STUN_BINDING_REQUEST,
        transactionId = Random.nextBytes(12),
        attributes = listOfNotNull(
            STUN_USERNAME to username.toByteArray(),
            (STUN_USE_CANDIDATE to ByteArray(0)).takeIf { useCandidate },
        ),
        password = password,
    )

    private fun datagram(bytes: ByteArray, from: InetSocketAddress) =
        DatagramPacket(Unpooled.wrappedBuffer(bytes), server, from)

    private fun ByteBuffer.toBytes() = ByteArray(remaining()).also { get(it) }
}
