package org.koremc.network.sctp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataChannelsTest {
    private val client = TestClient().apply { handshake() }
    private val channels = DataChannels(client.association)

    @Test
    fun `open is acknowledged on the same stream`() {
        val event = assertIs<ChannelEvent.Opened>(channels.receive(open(stream = 0, label = "ReliableDataChannel", channelType = 0x00)))

        assertEquals("ReliableDataChannel", event.channel.label)
        assertTrue(event.channel.ordered)
        assertNull(event.channel.maxRetransmits)

        val ack = client.sentChunks<DataChunk>().single()
        assertEquals(listOf(0, 50), listOf(ack.streamId, ack.ppid))
        assertContentEquals(byteArrayOf(0x02), ack.payload)
        assertTrue(!ack.unordered)
    }

    @Test
    fun `unreliable channel options are read from the open message`() {
        val event = assertIs<ChannelEvent.Opened>(channels.receive(open(stream = 2, label = "UnreliableDataChannel", channelType = 0x81)))

        assertEquals(false, event.channel.ordered)
        assertEquals(0, event.channel.maxRetransmits)
        assertEquals(event.channel, channels["UnreliableDataChannel"])
    }

    @Test
    fun `binary and empty messages are delivered`() {
        channels.receive(open(stream = 0, label = "ReliableDataChannel", channelType = 0x00))

        val binary = assertIs<ChannelEvent.Message>(channels.receive(SctpMessage(0, 53, byteArrayOf(9, 8))))
        val empty = assertIs<ChannelEvent.Message>(channels.receive(SctpMessage(0, 57, byteArrayOf(0))))

        assertContentEquals(byteArrayOf(9, 8), binary.data)
        assertContentEquals(ByteArray(0), empty.data)
    }

    @Test
    fun `messages on streams without a channel are ignored`() {
        assertNull(channels.receive(SctpMessage(6, 53, byteArrayOf(1))))
    }

    @Test
    fun `sends follow the channel's reliability`() {
        val unreliable = assertIs<ChannelEvent.Opened>(channels.receive(open(stream = 2, label = "UnreliableDataChannel", channelType = 0x81))).channel
        client.sent.clear()

        unreliable.send(byteArrayOf(1, 2))
        unreliable.send(ByteArray(0))

        val (data, empty) = client.sentChunks<DataChunk>()
        assertTrue(data.unordered && data.ppid == 53)
        assertEquals(57, empty.ppid)
        assertContentEquals(byteArrayOf(0), empty.payload)
    }

    private fun open(stream: Int, label: String, channelType: Int) = SctpMessage(stream, 50, dcepOpen(label, channelType))
}
