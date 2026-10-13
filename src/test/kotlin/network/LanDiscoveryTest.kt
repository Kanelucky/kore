package org.koremc.network

import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.socket.DatagramPacket
import org.koremc.network.rtc.Fingerprint
import org.koremc.network.rtc.IceCredentials
import org.koremc.network.rtc.SAMPLE_OFFER
import org.koremc.network.rtc.SdpAnswer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LanDiscoveryTest {
    private val info = JoinInfo(name = "Dedicated Server", protocol = 2193, version = "1.26.51", level = "Creative level", players = 0, maxPlayers = 10, gameType = 1)
    private val serverId = 99L
    private val game = InetSocketAddress("192.168.1.20", 7551)

    private val answer = SdpAnswer(
        sessionId = 1,
        mid = "0",
        ice = IceCredentials("abcd", "abcdefghijklmnopqrstuvwx"),
        fingerprint = Fingerprint("sha-256", "AA:BB"),
        identity = "e30=",
        setup = "passive",
        sctpPort = 5000,
        maxMessageSize = 262144,
        candidates = emptyList(),
    )
    private val offeredBy = mutableListOf<String>()
    private val channel = EmbeddedChannel(
        LanDiscovery(serverId, onlineMode = true, status = { info }) { networkId, _ -> answer.also { offeredBy += networkId } },
    )

    private fun hex(text: String) = ByteBufUtil.decodeHexDump(text)

    @Test
    fun `requests games send, as captured from them, read as requests`() {
        // Two games looking for others, from an older version and a newer one.
        val older = hex("b3eca3eb83a6fcb079faf2eae2bf8abbaadb5906bc42bd63a0056274a26e013f6b9027ddcd144fe3ada066f65b76e8faff0f8709e13bf6858e2051c321db615e")
        val newer = hex("ba539dab685df1c59ba406ec900f48e8249d28821c6e7149309d3fc289002201ee5a9a7b4fec49402ac54510cb744a96ff0f8709e13bf6858e2051c321db615e")

        for (request in listOf(older, newer)) {
            val packet = assertNotNull(decodeDiscovery(request))
            assertEquals(0, packet.kind)
            assertEquals(0, packet.body.size)
        }
        assertNull(decodeDiscovery(older.copyOf().also { it[0] = (it[0] + 1).toByte() }), "a checksum that doesn't match")
        assertNull(decodeDiscovery(ByteArray(10)))
    }

    @Test
    fun `what the server says of itself is laid out as the game's own servers lay it out`() {
        val data = serverData(info, onlineMode = true, advertisement = "9bb64bcf14727bdb")
        val length = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(
            "071044656469636174656420536572766572a22207312e32362e35310e4372656174697665206c6576656c0014020000" +
                "0100103962623634626366313437323762646208",
            String(data, 4, length),
        )
        assertEquals("0101", String(serverData(info, onlineMode = false, advertisement = "9bb64bcf14727bdb"), 4 + 96, 4), "taking players who aren't signed in")
    }

    @Test
    fun `a game looking for others hears back what the server is, from the server`() {
        val request = hex("ba539dab685df1c59ba406ec900f48e8249d28821c6e7149309d3fc289002201ee5a9a7b4fec49402ac54510cb744a96ff0f8709e13bf6858e2051c321db615e")
        channel.writeInbound(DatagramPacket(Unpooled.wrappedBuffer(request), game, game))

        val reply = channel.readOutbound<DatagramPacket>()
        assertEquals(game, reply.recipient())
        val packet = assertNotNull(decodeDiscovery(ByteBufUtil.getBytes(reply.content())))
        assertEquals(1, packet.kind)
        assertEquals(serverId, packet.sender)
        val hex = String(packet.body, 4, packet.body.size - 4)
        assertEquals(1, hex.indexOf("1044656469636174656420536572766572") / 2, "its name, after the version")
    }

    @Test
    fun `a game joins through discovery, its offer answered as an offer over HTTP is`() {
        val signal = "CONNECTREQUEST 12345 $SAMPLE_OFFER".toByteArray()
        val body = ByteBuffer.allocate(12 + signal.size).order(ByteOrder.LITTLE_ENDIAN).putLong(serverId).putInt(signal.size).put(signal).array()
        channel.writeInbound(DatagramPacket(Unpooled.wrappedBuffer(encodeDiscovery(2, 777, body)), game, game))

        assertEquals(listOf("777"), offeredBy)
        val packet = assertNotNull(decodeDiscovery(ByteBufUtil.getBytes(channel.readOutbound<DatagramPacket>().content())))
        assertEquals(2, packet.kind)
        val reply = ByteBuffer.wrap(packet.body).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(777L, reply.long, "to the game that asked")
        val text = String(packet.body, 12, reply.int)
        assertEquals("CONNECTRESPONSE 12345 ${answer.encode()}", text)

        // Messages for another server are none of this one's business.
        val elsewhere = ByteBuffer.allocate(12 + signal.size).order(ByteOrder.LITTLE_ENDIAN).putLong(1234).putInt(signal.size).put(signal).array()
        channel.writeInbound(DatagramPacket(Unpooled.wrappedBuffer(encodeDiscovery(2, 777, elsewhere)), game, game))
        assertNull(channel.readOutbound<DatagramPacket>())
    }
}
