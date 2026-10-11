package org.koremc.network.sctp

import java.nio.ByteBuffer
import kotlin.time.Duration

internal const val CLIENT_TAG = 0x0C11E47

/** A DATA_CHANNEL_OPEN message (RFC 8832 §5.1) as a client would send it. */
internal fun dcepOpen(label: String, channelType: Int, reliability: Int = 0): ByteArray {
    val labelBytes = label.toByteArray()
    return ByteBuffer.allocate(12 + labelBytes.size)
        .put(0x03)
        .put(channelType.toByte())
        .putShort(0)
        .putInt(reliability)
        .putShort(labelBytes.size.toShort())
        .putShort(0)
        .put(labelBytes)
        .array()
}

/** Plays the client's side of an association with hand-built packets, recording everything Kore sends. */
internal class TestClient {
    val timers = ManualScheduler()
    val sent = mutableListOf<SctpPacket>()
    val sentSizes = mutableListOf<Int>()
    val association = SctpAssociation(
        transmit = { packet ->
            sentSizes += packet.remaining()
            sent += checkNotNull(parsePacket(packet)) { "Kore sent a packet that doesn't parse" }
        },
        scheduler = timers,
    )

    var serverTag = 0
        private set
    var serverInitialTsn = Tsn(0)
        private set
    val initialTsn = Tsn(1000)

    fun deliver(vararg chunks: Chunk, tag: Int = serverTag): List<SctpMessage> =
        association.receive(SctpPacket(tag, chunks.toList()).encode())

    fun handshake(forwardTsn: Boolean = true) {
        val parameters = if (forwardTsn) listOf(Parameter(0xC000, ByteArray(0))) else emptyList()
        deliver(Init(false, CLIENT_TAG, 1 shl 20, 1024, 1024, initialTsn, parameters), tag = 0)
        val initAck = sentChunks<Init>().single()
        serverTag = initAck.initiateTag
        serverInitialTsn = initAck.initialTsn
        deliver(CookieEcho(checkNotNull(initAck.parameter(7))))
        sent.clear()
        sentSizes.clear()
    }

    fun data(
        offset: Int,
        payload: ByteArray,
        stream: Int = 0,
        ssn: Int = 0,
        ppid: Int = 53,
        unordered: Boolean = false,
        beginning: Boolean = true,
        ending: Boolean = true,
    ) = DataChunk(initialTsn + offset, stream, ssn, ppid, payload, unordered, beginning, ending)

    inline fun <reified T : Chunk> sentChunks(): List<T> = sent.flatMap { it.chunks }.filterIsInstance<T>()
}

/** Collects scheduled tasks so tests decide when timers fire. */
internal class ManualScheduler : Scheduler {
    private val tasks = mutableListOf<() -> Unit>()

    val pending get() = tasks.size

    override fun schedule(delay: Duration, task: () -> Unit): () -> Unit {
        tasks += task
        return { tasks.remove(task) }
    }

    fun fire() {
        val due = tasks.toList()
        tasks.clear()
        due.forEach { it() }
    }
}
