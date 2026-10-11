package org.koremc.network.sctp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SctpAssociationTest {
    private val client = TestClient()

    @Test
    fun `handshake establishes the association`() {
        client.deliver(Init(false, CLIENT_TAG, 1 shl 20, 1024, 1024, client.initialTsn), tag = 0)

        val initAck = client.sent.single()
        assertEquals(CLIENT_TAG, initAck.verificationTag)
        val cookie = (initAck.chunks.single() as Init).parameter(7)
        assertFalse(client.association.isEstablished)

        client.deliver(CookieEcho(checkNotNull(cookie)), tag = (initAck.chunks.single() as Init).initiateTag)

        assertTrue(client.association.isEstablished)
        assertEquals(CookieAck, client.sent.last().chunks.single())
    }

    @Test
    fun `packets with the wrong verification tag are ignored`() {
        client.handshake()

        val messages = client.deliver(client.data(0, byteArrayOf(1)), tag = client.serverTag + 1)

        assertTrue(messages.isEmpty())
        assertTrue(client.sent.isEmpty())
    }

    @Test
    fun `data is delivered and acknowledged`() {
        client.handshake()

        val messages = client.deliver(client.data(0, "hi".toByteArray(), stream = 4, ppid = 51))

        assertEquals(listOf("hi"), messages.map { it.payload.decodeToString() })
        assertEquals(4, messages.single().streamId)
        assertEquals(51, messages.single().ppid)
        assertEquals(client.initialTsn, client.sentChunks<Sack>().single().cumulativeTsn)
    }

    @Test
    fun `fragments are reassembled in any order`() {
        client.handshake()

        val last = client.deliver(client.data(2, "c".toByteArray(), beginning = false, ending = true))
        val first = client.deliver(client.data(0, "a".toByteArray(), beginning = true, ending = false))
        val middle = client.deliver(client.data(1, "b".toByteArray(), beginning = false, ending = false))

        assertTrue(last.isEmpty() && first.isEmpty())
        assertEquals(listOf("abc"), middle.map { it.payload.decodeToString() })
        assertEquals(listOf(3..3), client.sentChunks<Sack>().first().gaps)
        assertEquals(client.initialTsn + 2, client.sentChunks<Sack>().last().cumulativeTsn)
    }

    @Test
    fun `ordered messages wait for their turn`() {
        client.handshake()

        val early = client.deliver(client.data(1, "second".toByteArray(), ssn = 1))
        val late = client.deliver(client.data(0, "first".toByteArray(), ssn = 0))

        assertTrue(early.isEmpty())
        assertEquals(listOf("first", "second"), late.map { it.payload.decodeToString() })
    }

    @Test
    fun `unordered messages skip the queue`() {
        client.handshake()

        val messages = client.deliver(client.data(1, "now".toByteArray(), unordered = true))

        assertEquals(listOf("now"), messages.map { it.payload.decodeToString() })
    }

    @Test
    fun `duplicates are reported`() {
        client.handshake()

        client.deliver(client.data(0, byteArrayOf(1)))
        val again = client.deliver(client.data(0, byteArrayOf(1)))

        assertTrue(again.isEmpty())
        assertEquals(listOf(client.initialTsn), client.sentChunks<Sack>().last().duplicates)
    }

    @Test
    fun `forward tsn skips data the client gave up on`() {
        client.handshake()

        val waiting = client.deliver(client.data(1, "kept".toByteArray(), ssn = 1))
        val released = client.deliver(ForwardTsn(client.initialTsn, listOf(0 to 0)))

        assertTrue(waiting.isEmpty())
        assertEquals(listOf("kept"), released.map { it.payload.decodeToString() })
        assertEquals(client.initialTsn + 1, client.sentChunks<Sack>().last().cumulativeTsn)
    }

    @Test
    fun `heartbeats are echoed`() {
        client.handshake()

        client.deliver(Heartbeat(ack = false, info = byteArrayOf(0, 1, 0, 5, 42)))

        val ack = client.sentChunks<Heartbeat>().single()
        assertTrue(ack.ack)
        assertContentEquals(byteArrayOf(0, 1, 0, 5, 42), ack.info)
    }

    @Test
    fun `large messages are split to fit the mtu`() {
        client.handshake()

        client.association.send(streamId = 0, ppid = 53, payload = ByteArray(5000) { it.toByte() })

        // The initial congestion window holds four full chunks. Acknowledging them releases the fifth.
        assertEquals(4, client.sentChunks<DataChunk>().size)
        client.deliver(Sack(client.sentChunks<DataChunk>().last().tsn, 1 shl 20, emptyList(), emptyList()))

        val chunks = client.sentChunks<DataChunk>()
        assertEquals(5, chunks.size)
        assertTrue(client.sentSizes.all { it <= MTU })
        assertTrue(chunks.first().beginning && chunks.last().ending)
        assertTrue(chunks.drop(1).none { it.beginning } && chunks.dropLast(1).none { it.ending })
        assertEquals((0 until chunks.size).map { client.serverInitialTsn + it }, chunks.map { it.tsn })
        assertContentEquals(ByteArray(5000) { it.toByte() }, chunks.flatMap { it.payload.asList() }.toByteArray())
    }

    @Test
    fun `ordered messages on a stream get consecutive sequence numbers`() {
        client.handshake()

        repeat(3) { client.association.send(streamId = 2, ppid = 53, payload = byteArrayOf(it.toByte())) }

        assertEquals(listOf(0, 1, 2), client.sentChunks<DataChunk>().map { it.ssn })
    }

    @Test
    fun `acknowledged data is not retransmitted`() {
        client.handshake()
        client.association.send(streamId = 0, ppid = 53, payload = byteArrayOf(1))

        client.deliver(Sack(client.serverInitialTsn, 1 shl 20, emptyList(), emptyList()))
        client.sent.clear()
        client.timers.fire()

        assertTrue(client.sent.isEmpty())
        assertEquals(0, client.timers.pending)
    }

    @Test
    fun `unacknowledged data is retransmitted after the timeout`() {
        client.handshake()
        client.association.send(streamId = 0, ppid = 53, payload = byteArrayOf(1))
        client.sent.clear()

        client.timers.fire()

        assertEquals(listOf(client.serverInitialTsn), client.sentChunks<DataChunk>().map { it.tsn })
    }

    @Test
    fun `three missing reports trigger fast retransmit`() {
        client.handshake()
        repeat(4) { client.association.send(streamId = 0, ppid = 53, payload = byteArrayOf(it.toByte())) }
        client.sent.clear()

        // The first chunk went missing; the other three arrived.
        repeat(3) { client.deliver(Sack(client.serverInitialTsn - 1, 1 shl 20, listOf(2..4), emptyList())) }

        assertEquals(listOf(client.serverInitialTsn), client.sentChunks<DataChunk>().map { it.tsn })
    }

    @Test
    fun `unreliable messages are abandoned instead of retransmitted`() {
        client.handshake()
        client.association.send(streamId = 2, ppid = 53, payload = byteArrayOf(1), ordered = false, maxRetransmits = 0)
        client.sent.clear()

        client.timers.fire()

        assertTrue(client.sentChunks<DataChunk>().isEmpty())
        assertEquals(client.serverInitialTsn, client.sentChunks<ForwardTsn>().single().newCumulativeTsn)
    }

    @Test
    fun `unreliable messages stay reliable when the client lacks forward tsn`() {
        client.handshake(forwardTsn = false)
        client.association.send(streamId = 2, ppid = 53, payload = byteArrayOf(1), ordered = false, maxRetransmits = 0)
        client.sent.clear()

        client.timers.fire()

        assertEquals(1, client.sentChunks<DataChunk>().size)
        assertTrue(client.sentChunks<ForwardTsn>().isEmpty())
    }

    @Test
    fun `abort closes the association`() {
        client.handshake()

        client.deliver(Abort(ByteArray(0)))

        assertTrue(client.association.isClosed)
    }
}
