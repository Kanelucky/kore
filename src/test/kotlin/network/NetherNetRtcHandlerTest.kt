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
import org.koremc.network.sctp.Abort
import org.koremc.network.sctp.Chunk
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
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

private const val NETWORK_ID = "9876543210123456789"

class NetherNetRtcHandlerTest {
    private val server = InetSocketAddress("203.0.113.7", 19132)
    private val client = InetSocketAddress("198.51.100.4", 52000)

    private val serverCertificate = DtlsCertificate.generate()
    private val clientCertificate = DtlsCertificate.generate()

    private val peer = Peer(
        networkId = NETWORK_ID,
        localIce = IceCredentials("kore", "korekorekorekorekorekore"),
        remoteUfrag = "game",
        remoteFingerprints = listOf(clientCertificate.fingerprint),
        maxMessageSize = 262144,
    )
    private val peers = Peers().apply { add(peer) }

    // What game code saw, in order.
    private val events = mutableListOf<String>()
    private var onConnect: (NetherNetConnection) -> Unit = {}

    private val channel = EmbeddedChannel(
        NetherNetRtcHandler(
            peers = peers,
            dtls = DtlsContext(serverCertificate),
            onConnect = { connection ->
                events += "connect ${connection.networkId}"
                onConnect(connection)
            },
            onMessage = { _, message -> events += "message ${message.toList()}" },
            onDisconnect = { connection, reason -> events += "disconnect ${connection.networkId} $reason" },
        ),
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
        val stack = ClientStack()

        val replies = stack.open("ReliableDataChannel", stream = 0)
        // Two NetherNet fragments: header 1 (one more to come), then header 0.
        stack.message(stream = 0, payload = byteArrayOf(1, 4))
        stack.message(stream = 0, payload = byteArrayOf(0, 2))

        assertContentEquals(byteArrayOf(0x02), replies.filterIsInstance<DataChunk>().single().payload)
        assertNotNull(peer.channels?.get("ReliableDataChannel"))
        assertEquals(listOf("connect $NETWORK_ID", "message [4, 2]"), events)
    }

    @Test
    fun `connect waits for the reliable channel`() {
        val stack = ClientStack()

        stack.open("UnreliableDataChannel", stream = 2, channelType = 0x81)
        assertTrue(events.isEmpty())

        stack.open("ReliableDataChannel", stream = 0)
        assertEquals(listOf("connect $NETWORK_ID"), events)
    }

    @Test
    fun `disconnect follows the client closing`() {
        val stack = ClientStack()
        stack.open("ReliableDataChannel", stream = 0)

        stack.exchange(Abort(ByteArray(0)))

        assertEquals(listOf("connect $NETWORK_ID", "disconnect $NETWORK_ID CLOSED_BY_CLIENT"), events)
        assertNull(peers[peer.localIce.ufrag])
    }

    @Test
    fun `clients that go quiet time out`() {
        ClientStack().open("ReliableDataChannel", stream = 0)

        peer.lastSeen = System.nanoTime() - 1.minutes.inWholeNanoseconds
        runSweep()

        assertEquals(listOf("connect $NETWORK_ID", "disconnect $NETWORK_ID TIMED_OUT"), events)
    }

    @Test
    fun `clients that never connected leave without a disconnect`() {
        connect()

        peer.lastSeen = System.nanoTime() - 1.minutes.inWholeNanoseconds
        runSweep()

        assertTrue(events.isEmpty())
        assertNull(peers[peer.localIce.ufrag])
    }

    @Test
    fun `stopping the server disconnects everyone`() {
        ClientStack().open("ReliableDataChannel", stream = 0)

        channel.close()

        assertEquals(listOf("connect $NETWORK_ID", "disconnect $NETWORK_ID SERVER_STOPPED"), events)
    }

    @Test
    fun `a failing callback doesn't take the connection down`() {
        onConnect = { error("game code bug") }
        val stack = ClientStack()

        stack.open("ReliableDataChannel", stream = 0)
        stack.message(stream = 0, payload = byteArrayOf(0, 9))

        assertEquals(listOf("connect $NETWORK_ID", "message [9]"), events)
    }

    /** A client that has passed ICE, DTLS and the SCTP handshake, and can open channels and send on them. */
    private inner class ClientStack {
        private val dtls = connect()
        private var tag = 0
        private var nextTsn = Tsn(1)
        private val nextSsn = HashMap<Int, Int>()

        init {
            val initAck = exchange(Init(false, 0x5EED, 1 shl 20, 1024, 1024, nextTsn)).filterIsInstance<Init>().single()
            tag = initAck.initiateTag
            check(CookieAck in exchange(CookieEcho(checkNotNull(initAck.parameter(7)))))
        }

        fun open(label: String, stream: Int, channelType: Int = 0x00) = exchange(data(stream, ppid = 50, dcepOpen(label, channelType)))

        fun message(stream: Int, payload: ByteArray) = exchange(data(stream, ppid = 53, payload))

        /** Sends [chunks] in one packet and returns every chunk Kore sends back. */
        fun exchange(vararg chunks: Chunk): List<Chunk> {
            dtls.write(SctpPacket(tag, chunks.toList()).encode())
            return pump(dtls).flatMap { checkNotNull(parsePacket(it)) { "Kore sent a malformed SCTP packet" }.chunks }
        }

        private fun data(stream: Int, ppid: Int, payload: ByteArray): DataChunk {
            val ssn = nextSsn.getOrDefault(stream, 0).also { nextSsn[stream] = it + 1 }
            return DataChunk(nextTsn, stream, ssn, ppid, payload).also { nextTsn += 1 }
        }
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

    private fun runSweep() {
        channel.advanceTimeBy(5, TimeUnit.SECONDS)
        channel.runScheduledPendingTasks()
    }

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
