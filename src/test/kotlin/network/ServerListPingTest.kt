package org.koremc.network

import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.socket.DatagramPacket
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ServerListPingTest {
    private val info = JoinInfo(name = "Kore Server", protocol = 2193, version = "1.26.50", level = "Bedrock level", players = 2, maxPlayers = 20, gameType = 1)
    private val ping = ServerListPing(serverId = 42, port = 19132, portV6 = 19133, status = { info })

    private val magic = ByteBufUtil.decodeHexDump("00ffff00fefefefefdfdfdfd12345678")

    @Test
    fun `a server list's ping hears the server's name, version, players and level, as the game's dedicated server gives them`() {
        val request = Unpooled.buffer().writeByte(0x01).writeLong(123456789).writeBytes(magic).writeLong(7)

        val pong = assertNotNull(ping.answer(request))

        assertEquals(0x1C, pong.readUnsignedByte().toInt())
        assertEquals(123456789L, pong.readLong(), "the client's time, back")
        assertEquals(42L, pong.readLong())
        assertEquals(magic.toList(), ByteBufUtil.getBytes(pong.readBytes(16)).toList())
        val advertised = pong.readCharSequence(pong.readUnsignedShort(), Charsets.UTF_8).toString()
        assertEquals("MCPE;Kore Server;2193;1.26.50;2;20;42;Bedrock level;Creative;1;19132;19133;0;", advertised)
    }

    @Test
    fun `the IPv6 port answers pings, back to whoever sent them, and nothing else`() {
        val channel = EmbeddedChannel(ServerListPingHandler(ping))
        val player = InetSocketAddress("::1", 50000)

        channel.writeInbound(DatagramPacket(Unpooled.buffer().writeByte(0x01).writeLong(1).writeBytes(magic).writeLong(7), player, player))
        val reply = channel.readOutbound<DatagramPacket>()
        assertEquals(player, reply.recipient())
        assertEquals(0x1C, reply.content().getUnsignedByte(0).toInt())

        channel.writeInbound(DatagramPacket(Unpooled.buffer().writeByte(22).writeZero(30), player, player))
        assertNull(channel.readOutbound<DatagramPacket>())
    }

    @Test
    fun `a RakNet connection is turned away as a version that won't do, and STUN and anything else go by`() {
        val request = Unpooled.buffer().writeByte(0x05).writeBytes(magic).writeByte(11).writeZero(100)

        val reply = assertNotNull(ping.answer(request))

        assertEquals(listOf(0x19, 12), listOf(reply.readUnsignedByte().toInt(), reply.readUnsignedByte().toInt()))
        // A STUN binding success starts 0x01 too, but carries STUN's magic cookie, not RakNet's.
        val stun = Unpooled.buffer().writeShort(0x0101).writeShort(0).writeInt(0x2112A442).writeZero(12)
        assertNull(ping.answer(stun))
        assertNull(ping.answer(Unpooled.buffer().writeByte(22).writeZero(30)))
    }
}
