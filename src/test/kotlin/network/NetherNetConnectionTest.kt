package org.koremc.network

import org.koremc.network.sctp.ChannelEvent
import org.koremc.network.sctp.DataChannel
import org.koremc.network.sctp.DataChannels
import org.koremc.network.sctp.DataChunk
import org.koremc.network.sctp.SctpMessage
import org.koremc.network.sctp.TestClient
import org.koremc.network.sctp.dcepOpen
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetherNetConnectionTest {
    private val client = TestClient().apply { handshake() }
    private val channels = DataChannels(client.association)
    private val reliable = openChannel(stream = 0, label = "ReliableDataChannel", channelType = 0x00)
    private val unreliable = openChannel(stream = 2, label = "UnreliableDataChannel", channelType = 0x81)
    private val connection = NetherNetConnection("9876543210123456789", maxFrameSize = 100).apply {
        bind(reliable)
        bind(unreliable)
    }

    init {
        client.sent.clear()
    }

    @Test
    fun `small messages go out whole with a zero header`() {
        assertEquals(listOf(listOf<Byte>(0, 1, 2)), frame(byteArrayOf(1, 2), maxFrameSize = 100).map { it.asList() })
        assertEquals(listOf(listOf<Byte>(0)), frame(ByteArray(0), maxFrameSize = 100).map { it.asList() })
        assertEquals(1, frame(ByteArray(99), maxFrameSize = 100).size)
    }

    @Test
    fun `large messages are split with a countdown`() {
        val message = ByteArray(250) { it.toByte() }
        val frames = frame(message, maxFrameSize = 100)

        assertEquals(listOf(2, 1, 0), frames.map { it[0].toInt() })
        assertTrue(frames.all { it.size <= 100 })
        assertContentEquals(message, frames.flatMap { it.drop(1) }.toByteArray())
    }

    @Test
    fun `messages needing more than 256 fragments are refused`() {
        frame(ByteArray(256 * 9), maxFrameSize = 10)
        assertFailsWith<IllegalArgumentException> { frame(ByteArray(256 * 9 + 1), maxFrameSize = 10) }
    }

    @Test
    fun `fragments are rejoined`() {
        val assembler = FrameAssembler()
        val message = ByteArray(250) { it.toByte() }
        val results = frame(message, maxFrameSize = 100).map { assembler.add(it) }

        assertNull(results[0])
        assertNull(results[1])
        assertContentEquals(message, results[2])
    }

    @Test
    fun `fragments out of sequence are rejected and the next message still gets through`() {
        val assembler = FrameAssembler()
        assembler.add(byteArrayOf(2, 1))

        assertFailsWith<FramingException> { assembler.add(byteArrayOf(0, 2)) }
        assertContentEquals(byteArrayOf(3), assembler.add(byteArrayOf(0, 3)))
    }

    @Test
    fun `frames without a header are rejected`() {
        assertFailsWith<FramingException> { FrameAssembler().add(ByteArray(0)) }
    }

    @Test
    fun `reassembly stops at its size limit`() {
        val assembler = FrameAssembler()
        val fragment = ByteArray(1 shl 20)

        assertFailsWith<FramingException> {
            for (remaining in 9 downTo 0) assembler.add(fragment.also { it[0] = remaining.toByte() })
        }
    }

    @Test
    fun `reliable sends are fragmented across data channel messages`() {
        assertTrue(connection.send(ByteArray(250) { 7 }))

        val frames = client.sentChunks<DataChunk>()
        assertTrue(frames.all { it.streamId == 0 && !it.unordered })
        assertEquals(listOf(2, 1, 0), frames.map { it.payload[0].toInt() })
    }

    @Test
    fun `unreliable sends that don't fit whole are dropped`() {
        assertFalse(connection.send(ByteArray(100), reliable = false))
        assertTrue(client.sentChunks<DataChunk>().isEmpty())

        assertTrue(connection.send(ByteArray(99), reliable = false))
        val frame = client.sentChunks<DataChunk>().single()
        assertTrue(frame.streamId == 2 && frame.unordered && frame.payload[0].toInt() == 0)
    }

    @Test
    fun `nothing is sent before the reliable channel opens`() {
        val fresh = NetherNetConnection("1", maxFrameSize = 100)

        assertFalse(fresh.isOpen)
        assertFalse(fresh.send(byteArrayOf(1)))
    }

    @Test
    fun `received messages lose their header`() {
        assertNull(connection.receive(reliable, byteArrayOf(1, 10)))
        assertContentEquals(byteArrayOf(10, 20), connection.receive(reliable, byteArrayOf(0, 20)))
        assertContentEquals(byteArrayOf(30), connection.receive(unreliable, byteArrayOf(0, 30)))
    }

    @Test
    fun `unreliable fragments are a protocol error`() {
        assertFailsWith<FramingException> { connection.receive(unreliable, byteArrayOf(1, 30)) }
    }

    @Test
    fun `channels NetherNet doesn't use are refused`() {
        assertFalse(connection.bind(openChannel(stream = 4, label = "Chat", channelType = 0x00)))
    }

    private fun openChannel(stream: Int, label: String, channelType: Int): DataChannel {
        val event = channels.receive(SctpMessage(stream, 50, dcepOpen(label, channelType)))
        return (event as ChannelEvent.Opened).channel
    }
}
