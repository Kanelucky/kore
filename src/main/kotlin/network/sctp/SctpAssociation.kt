package org.koremc.network.sctp

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.TreeMap
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/** The largest packet Kore sends. With DTLS record overhead it still fits a 1200-byte datagram, which any path carries. */
internal const val MTU = 1100

private const val RECEIVE_WINDOW = 1 shl 20
private const val MAX_STREAMS = 65535

private const val PARAM_STATE_COOKIE = 7
private const val PARAM_SUPPORTED_EXTENSIONS = 0x8008
private const val PARAM_FORWARD_TSN_SUPPORTED = 0xC000

// Sized for real-time traffic like other WebRTC stacks, not RFC 9260's one-second minimum.
private val RTO_INITIAL = 500.milliseconds
private val RTO_MIN = 200.milliseconds
private val RTO_MAX = 10.seconds

private val random = SecureRandom()

/** Runs a task later on the association's thread, returning a function that cancels it. */
fun interface Scheduler {
    fun schedule(delay: Duration, task: () -> Unit): () -> Unit
}

/** A complete message, reassembled from its DATA chunks. */
class SctpMessage(val streamId: Int, val ppid: Int, val payload: ByteArray)

/**
 * The server side of an SCTP association (RFC 9260) running over DTLS, cut
 * down to what WebRTC data channels need: one path, no multihoming, and
 * partial reliability (RFC 3758) for unreliable channels. The client always
 * starts it.
 *
 * Not thread-safe. Everything happens on the event loop that owns the DTLS transport.
 */
class SctpAssociation(private val transmit: (ByteBuffer) -> Unit, private val scheduler: Scheduler) {
    private enum class State { AWAITING_INIT, AWAITING_COOKIE, ESTABLISHED, SHUTTING_DOWN, CLOSED }

    private var state = State.AWAITING_INIT
    private val localTag = random.nextInt().let { if (it == 0) 1 else it }
    private val cookie = ByteArray(16).also(random::nextBytes)
    private var peerTag = 0
    private var forwardTsnSupported = false

    // Receiving: the highest TSN with nothing missing below it, plus whatever arrived beyond that.
    private var peerCumulativeTsn = Tsn(0)
    private val receivedAhead = HashSet<Tsn>()
    private val duplicates = mutableListOf<Tsn>()
    private val reassembly = Reassembly()

    // Sending. Outstanding chunks stay in TSN order until the peer acknowledges them cumulatively.
    private var nextTsn = Tsn(random.nextInt())
    private var cumulativeAck = nextTsn - 1
    private val outboundSsn = HashMap<Int, Int>()
    private var nextMessageId = 0L
    private val pending = ArrayDeque<Outbound>()
    private val outstanding = ArrayDeque<Outbound>()

    // Congestion control (RFC 9260 §7.2).
    private var peerWindow = 0
    private var congestionWindow = min(4 * MTU, max(2 * MTU, 4380))
    private var slowStartThreshold = Int.MAX_VALUE
    private var partialBytesAcked = 0
    private var fastRecoveryExit: Tsn? = null

    // Retransmission timer (RFC 9260 §6.3).
    private var rto = RTO_INITIAL
    private var smoothedRtt: Duration? = null
    private var rttVariation = Duration.ZERO
    private var cancelRetransmission: (() -> Unit)? = null

    val isEstablished get() = state == State.ESTABLISHED
    val isClosed get() = state == State.CLOSED

    /** Handles one packet from the peer and returns the messages it completed. */
    fun receive(packet: ByteBuffer): List<SctpMessage> {
        val parsed = parsePacket(packet) ?: return emptyList()
        val first = parsed.chunks.firstOrNull() ?: return emptyList()

        // Only INIT arrives before the peer knows our tag; everything else must carry it (RFC 9260 §8.5).
        val expectedTag = if (first is Init && !first.ack) 0 else localTag
        if (parsed.verificationTag != expectedTag) return emptyList()

        val messages = mutableListOf<SctpMessage>()
        var acknowledge = false
        for (chunk in parsed.chunks) {
            when (chunk) {
                is Init -> if (!chunk.ack) onInit(chunk)
                is CookieEcho -> onCookieEcho(chunk)
                is DataChunk -> acknowledge = onData(chunk, messages) || acknowledge
                is ForwardTsn -> acknowledge = onForwardTsn(chunk, messages) || acknowledge
                is Sack -> onSack(chunk)
                is Heartbeat -> if (!chunk.ack) sendControl(Heartbeat(ack = true, info = chunk.info))
                is Shutdown -> onShutdown(chunk)
                is Abort, ShutdownComplete -> {
                    close()
                    return messages
                }
                ShutdownAck, CookieAck, is OtherChunk -> Unit
            }
        }

        if (acknowledge) sendSack()
        flush()
        return messages
    }

    /**
     * Queues a message on [streamId], split across as many DATA chunks as it
     * needs. With [maxRetransmits] set, the message is abandoned rather than
     * retransmitted more often than that.
     */
    fun send(streamId: Int, ppid: Int, payload: ByteArray, ordered: Boolean = true, maxRetransmits: Int? = null) {
        check(state == State.ESTABLISHED) { "association isn't established" }
        require(payload.isNotEmpty()) { "SCTP can't carry empty messages" }

        val ssn = if (!ordered) 0 else outboundSsn.getOrDefault(streamId, 0).also { outboundSsn[streamId] = (it + 1) and 0xFFFF }
        val messageId = nextMessageId++
        val maxFragment = MTU - COMMON_HEADER_SIZE - DATA_HEADER_SIZE

        var offset = 0
        while (offset < payload.size) {
            val end = min(offset + maxFragment, payload.size)
            val chunk = DataChunk(
                tsn = nextTsn,
                streamId = streamId,
                ssn = ssn,
                ppid = ppid,
                payload = payload.copyOfRange(offset, end),
                unordered = !ordered,
                beginning = offset == 0,
                ending = end == payload.size,
            )
            pending += Outbound(chunk, messageId, maxRetransmits)
            nextTsn += 1
            offset = end
        }
        flush()
    }

    /** Stops timers and drops all state. The peer isn't told: it's either gone or already closed the association. */
    fun close() {
        stopRetransmissionTimer()
        pending.clear()
        outstanding.clear()
        state = State.CLOSED
    }

    private fun onInit(init: Init) {
        // A repeated INIT means our INIT ACK was lost, so it gets the same answer.
        if (state != State.AWAITING_INIT && state != State.AWAITING_COOKIE) return

        peerTag = init.initiateTag
        peerCumulativeTsn = init.initialTsn - 1
        peerWindow = init.window
        slowStartThreshold = init.window
        forwardTsnSupported = init.parameter(PARAM_FORWARD_TSN_SUPPORTED) != null ||
            init.parameter(PARAM_SUPPORTED_EXTENSIONS)?.any { (it.toInt() and 0xFF) == FORWARD_TSN } == true
        state = State.AWAITING_COOKIE

        // Kore already holds per-client state for ICE and DTLS, so the cookie is just a nonce to
        // compare, not the signed, stateless blob RFC 9260 uses to resist INIT floods.
        sendControl(
            Init(
                ack = true,
                initiateTag = localTag,
                window = RECEIVE_WINDOW,
                outboundStreams = MAX_STREAMS,
                inboundStreams = MAX_STREAMS,
                initialTsn = nextTsn,
                parameters = listOf(
                    Parameter(PARAM_STATE_COOKIE, cookie),
                    Parameter(PARAM_SUPPORTED_EXTENSIONS, byteArrayOf(FORWARD_TSN.toByte())),
                    Parameter(PARAM_FORWARD_TSN_SUPPORTED, ByteArray(0)),
                ),
            ),
        )
    }

    private fun onCookieEcho(echo: CookieEcho) {
        if (state == State.AWAITING_INIT || !echo.cookie.contentEquals(cookie)) return
        if (state == State.AWAITING_COOKIE) state = State.ESTABLISHED
        // Also answers a repeated COOKIE ECHO, in case our COOKIE ACK was lost.
        sendControl(CookieAck)
    }

    /** Returns whether the chunk calls for a SACK. */
    private fun onData(chunk: DataChunk, messages: MutableList<SctpMessage>): Boolean {
        if (state != State.ESTABLISHED) return false
        if (chunk.tsn <= peerCumulativeTsn || !receivedAhead.add(chunk.tsn)) {
            duplicates += chunk.tsn
            return true
        }
        advancePeerCumulativeTsn()
        messages += reassembly.add(chunk)
        return true
    }

    private fun onForwardTsn(forward: ForwardTsn, messages: MutableList<SctpMessage>): Boolean {
        if (state != State.ESTABLISHED) return false
        if (forward.newCumulativeTsn > peerCumulativeTsn) {
            peerCumulativeTsn = forward.newCumulativeTsn
            receivedAhead.removeAll { it <= peerCumulativeTsn }
            advancePeerCumulativeTsn()
            messages += reassembly.skip(peerCumulativeTsn, forward.streams)
        }
        return true
    }

    private fun advancePeerCumulativeTsn() {
        while (receivedAhead.remove(peerCumulativeTsn + 1)) peerCumulativeTsn += 1
    }

    private fun sendSack() {
        val gaps = mutableListOf<IntRange>()
        for (offset in receivedAhead.map { it - peerCumulativeTsn }.sorted()) {
            val last = gaps.lastOrNull()
            if (last != null && last.last + 1 == offset) gaps[gaps.lastIndex] = last.first..offset else gaps += offset..offset
        }
        sendControl(Sack(peerCumulativeTsn, RECEIVE_WINDOW - reassembly.bufferedBytes, gaps, duplicates.toList()))
        duplicates.clear()
    }

    private fun onSack(sack: Sack) {
        if (state != State.ESTABLISHED && state != State.SHUTTING_DOWN) return
        if (sack.cumulativeTsn < cumulativeAck) return

        val now = System.nanoTime()
        val advanced = sack.cumulativeTsn > cumulativeAck
        var newlyAcked = 0
        var rttSample: Duration? = null

        fun acknowledge(outbound: Outbound) {
            if (outbound.acked) return
            if (!outbound.abandoned) newlyAcked += outbound.size
            // Karn's rule: a retransmitted chunk can't say which transmission was acknowledged.
            if (outbound.transmissions == 1 && !outbound.markedForRetransmit) rttSample = (now - outbound.sentAt).nanoseconds
            outbound.acked = true
            outbound.markedForRetransmit = false
        }

        while (outstanding.isNotEmpty() && outstanding.first().chunk.tsn <= sack.cumulativeTsn) {
            acknowledge(outstanding.removeFirst())
        }
        cumulativeAck = sack.cumulativeTsn

        var highestGapAcked: Tsn? = null
        outstanding.firstOrNull()?.chunk?.tsn?.let { base ->
            for (gap in sack.gaps) for (offset in gap) {
                val tsn = sack.cumulativeTsn + offset
                val outbound = outstanding.getOrNull(tsn - base) ?: continue
                acknowledge(outbound)
                highestGapAcked = tsn
            }
        }

        // Fast retransmit (RFC 9260 §7.2.4): a chunk reported missing three times is presumed lost.
        highestGapAcked?.let { highest ->
            var lost = false
            for (outbound in outstanding) {
                if (outbound.chunk.tsn >= highest) break
                if (outbound.isInFlight && ++outbound.missIndications == 3) {
                    outbound.markedForRetransmit = true
                    lost = true
                }
            }
            if (lost && fastRecoveryExit == null) {
                slowStartThreshold = max(congestionWindow / 2, 4 * MTU)
                congestionWindow = slowStartThreshold
                partialBytesAcked = 0
                fastRecoveryExit = outstanding.last().chunk.tsn
            }
        }
        fastRecoveryExit?.let { if (cumulativeAck >= it) fastRecoveryExit = null }

        if (advanced && newlyAcked > 0 && fastRecoveryExit == null) {
            if (congestionWindow <= slowStartThreshold) {
                congestionWindow += min(newlyAcked, MTU)
            } else {
                partialBytesAcked += newlyAcked
                if (partialBytesAcked >= congestionWindow) {
                    partialBytesAcked -= congestionWindow
                    congestionWindow += MTU
                }
            }
        }

        rttSample?.let(::updateRto)
        peerWindow = sack.window

        when {
            outstanding.none { it.isInFlight } -> stopRetransmissionTimer()
            advanced -> restartRetransmissionTimer()
        }
    }

    private fun onShutdown(shutdown: Shutdown) {
        if (state != State.ESTABLISHED && state != State.SHUTTING_DOWN) return
        onSack(Sack(shutdown.cumulativeTsn, peerWindow, emptyList(), emptyList()))
        state = State.SHUTTING_DOWN
        stopRetransmissionTimer()
        sendControl(ShutdownAck)
    }

    /** Sends what the windows allow: retransmissions first, then new data, bundled into as few packets as fit. */
    private fun flush() {
        if (state != State.ESTABLISHED) return
        abandonExhausted()

        val now = System.nanoTime()
        var inFlight = outstanding.sumOf { if (it.isInFlight) it.size else 0 }
        val bundle = mutableListOf<Chunk>()
        var bundleSize = COMMON_HEADER_SIZE
        var sent = false

        fun stage(outbound: Outbound) {
            val size = padded(outbound.chunk.encodedLength())
            if (bundleSize + size > MTU) {
                sendPacket(bundle.toList())
                bundle.clear()
                bundleSize = COMMON_HEADER_SIZE
            }
            bundle += outbound.chunk
            bundleSize += size
            outbound.transmissions++
            outbound.sentAt = now
            outbound.markedForRetransmit = false
            outbound.missIndications = 0
            inFlight += outbound.size
            sent = true
        }

        // The congestion window always lets one chunk through, so a small window can't stall the association.
        fun fitsCongestionWindow(outbound: Outbound) = inFlight == 0 || inFlight + outbound.size <= congestionWindow

        for (outbound in outstanding) {
            if (!outbound.markedForRetransmit) continue
            if (!fitsCongestionWindow(outbound)) break
            stage(outbound)
        }

        while (pending.isNotEmpty()) {
            val outbound = pending.first()
            if (!outbound.abandoned) {
                if (!fitsCongestionWindow(outbound)) break
                if (inFlight > 0 && inFlight + outbound.size > peerWindow) break
                stage(outbound)
            }
            outstanding += pending.removeFirst()
        }

        if (bundle.isNotEmpty()) sendPacket(bundle)
        if (sent && cancelRetransmission == null) restartRetransmissionTimer()
        sendForwardTsnIfNeeded()
    }

    /** Abandons messages that would exceed their retransmission limit (RFC 3758 §3.5). */
    private fun abandonExhausted() {
        if (!forwardTsnSupported) return
        val exhausted = outstanding
            .filter { outbound -> outbound.markedForRetransmit && outbound.maxRetransmits?.let { outbound.transmissions > it } == true }
            .mapTo(HashSet()) { it.messageId }
        if (exhausted.isEmpty()) return

        for (outbound in outstanding.asSequence() + pending.asSequence()) {
            if (outbound.messageId in exhausted) {
                outbound.abandoned = true
                outbound.markedForRetransmit = false
            }
        }
    }

    /** Tells the peer to skip past abandoned chunks at the front of the queue. */
    private fun sendForwardTsnIfNeeded() {
        var advancedAckPoint = cumulativeAck
        val skipped = HashMap<Int, Int>()
        for (outbound in outstanding) {
            if (!outbound.acked && !outbound.abandoned) break
            advancedAckPoint = outbound.chunk.tsn
            if (outbound.abandoned && !outbound.chunk.unordered) skipped[outbound.chunk.streamId] = outbound.chunk.ssn
        }
        if (advancedAckPoint > cumulativeAck) sendControl(ForwardTsn(advancedAckPoint, skipped.toList()))
    }

    private fun onRetransmissionTimeout() {
        cancelRetransmission = null
        if (state != State.ESTABLISHED) return
        val lost = outstanding.filter { it.isInFlight }
        if (lost.isEmpty()) return

        // RFC 9260 §6.3.3 and §7.2.3: back off, collapse the window and resend from the oldest chunk.
        slowStartThreshold = max(congestionWindow / 2, 4 * MTU)
        congestionWindow = MTU
        partialBytesAcked = 0
        fastRecoveryExit = null
        rto = (rto * 2).coerceAtMost(RTO_MAX)
        lost.forEach { it.markedForRetransmit = true }
        flush()
    }

    private fun restartRetransmissionTimer() {
        cancelRetransmission?.invoke()
        cancelRetransmission = scheduler.schedule(rto, ::onRetransmissionTimeout)
    }

    private fun stopRetransmissionTimer() {
        cancelRetransmission?.invoke()
        cancelRetransmission = null
    }

    private fun updateRto(rtt: Duration) {
        val previous = smoothedRtt
        val smoothed = if (previous == null) {
            rttVariation = rtt / 2
            rtt
        } else {
            rttVariation = rttVariation * 0.75 + (previous - rtt).absoluteValue * 0.25
            previous * 0.875 + rtt * 0.125
        }
        smoothedRtt = smoothed
        rto = (smoothed + rttVariation * 4).coerceIn(RTO_MIN, RTO_MAX)
    }

    private fun sendControl(chunk: Chunk) = sendPacket(listOf(chunk))

    private fun sendPacket(chunks: List<Chunk>) = transmit(SctpPacket(peerTag, chunks).encode())
}

private class Outbound(val chunk: DataChunk, val messageId: Long, val maxRetransmits: Int?) {
    val size = DATA_HEADER_SIZE + chunk.payload.size
    var transmissions = 0
    var sentAt = 0L
    var acked = false
    var abandoned = false
    var markedForRetransmit = false
    var missIndications = 0

    val isInFlight get() = transmissions > 0 && !acked && !abandoned && !markedForRetransmit
}

/** Rebuilds fragmented messages and releases each ordered stream in sequence. */
private class Reassembly {
    private val fragments = TreeMap<Tsn, DataChunk>()
    private val expectedSsn = HashMap<Int, Int>()
    private val early = HashMap<Int, HashMap<Int, SctpMessage>>()

    var bufferedBytes = 0
        private set

    fun add(chunk: DataChunk): List<SctpMessage> {
        val message = if (chunk.beginning && chunk.ending) {
            SctpMessage(chunk.streamId, chunk.ppid, chunk.payload)
        } else {
            assemble(chunk) ?: return emptyList()
        }
        return if (chunk.unordered) listOf(message) else inOrder(chunk.ssn, message)
    }

    /** Drops fragments the peer gave up on, then releases anything that was only waiting on them. */
    fun skip(cumulativeTsn: Tsn, streams: List<Pair<Int, Int>>): List<SctpMessage> {
        val abandoned = fragments.headMap(cumulativeTsn, true)
        bufferedBytes -= abandoned.values.sumOf { it.payload.size }
        abandoned.clear()

        return buildList {
            for ((stream, ssn) in streams) {
                val next = (ssn + 1) and 0xFFFF
                if (!isAhead(next, expectedSsn.getOrDefault(stream, 0))) continue
                early[stream]?.entries?.removeIf { (waiting, message) ->
                    val skipped = waiting != next && !isAhead(waiting, next)
                    if (skipped) bufferedBytes -= message.payload.size
                    skipped
                }
                addAll(drain(stream, next))
            }
        }
    }

    // Fragments of one message always carry consecutive TSNs, from the B flag to the E flag.
    private fun assemble(chunk: DataChunk): SctpMessage? {
        fragments[chunk.tsn] = chunk
        bufferedBytes += chunk.payload.size

        var first = chunk
        while (!first.beginning) first = fragments[first.tsn - 1]?.takeIf { it.sameMessageAs(chunk) } ?: return null
        var last = chunk
        while (!last.ending) last = fragments[last.tsn + 1]?.takeIf { it.sameMessageAs(chunk) } ?: return null

        val parts = (0..(last.tsn - first.tsn)).map { checkNotNull(fragments.remove(first.tsn + it)) }
        val payload = ByteBuffer.allocate(parts.sumOf { it.payload.size })
        parts.forEach { payload.put(it.payload) }
        bufferedBytes -= payload.capacity()
        return SctpMessage(chunk.streamId, first.ppid, payload.array())
    }

    private fun inOrder(ssn: Int, message: SctpMessage): List<SctpMessage> {
        val stream = message.streamId
        val expected = expectedSsn.getOrDefault(stream, 0)
        return when {
            ssn == expected -> listOf(message) + drain(stream, (ssn + 1) and 0xFFFF)
            isAhead(ssn, expected) -> {
                early.getOrPut(stream) { HashMap() }[ssn] = message
                bufferedBytes += message.payload.size
                emptyList()
            }
            else -> emptyList()
        }
    }

    private fun drain(stream: Int, start: Int): List<SctpMessage> = buildList {
        var next = start
        while (true) {
            val message = early[stream]?.remove(next) ?: break
            bufferedBytes -= message.payload.size
            add(message)
            next = (next + 1) and 0xFFFF
        }
        expectedSsn[stream] = next
    }

    private fun DataChunk.sameMessageAs(other: DataChunk) =
        streamId == other.streamId && unordered == other.unordered && (unordered || ssn == other.ssn)

    /** 16-bit serial number comparison for stream sequence numbers. */
    private fun isAhead(ssn: Int, of: Int) = ssn != of && ((ssn - of) and 0xFFFF) < 0x8000
}
