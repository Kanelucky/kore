package org.koremc.network

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import kotlinx.serialization.json.Json
import org.koremc.network.rtc.Fingerprint
import org.koremc.network.rtc.IceCredentials
import org.koremc.network.rtc.SAMPLE_OFFER
import org.koremc.network.rtc.SdpAnswer
import org.slf4j.LoggerFactory
import java.net.SocketException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.text.Charsets.UTF_8

class NetherNetSignalingHandlerTest {
    private val info = JoinInfo(
        name = "Test Server",
        protocol = 1,
        version = "1.26.50",
        level = "world",
        players = 3,
        maxPlayers = 20,
        gameType = 1,
    )

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

    @Test
    fun `join info is served as json`() {
        val reply = send(HttpMethod.GET, "/v1/join")

        assertEquals(HttpResponseStatus.OK, reply.status)
        assertEquals("application/json; charset=utf-8", reply.contentType)
        assertEquals(info, Json.decodeFromString<JoinInfo>(reply.body))
    }

    @Test
    fun `query string does not affect routing`() {
        assertEquals(HttpResponseStatus.OK, send(HttpMethod.GET, "/v1/join?probe=1").status)
    }

    @Test
    fun `sdp offers are answered with sdp`() {
        val reply = send(HttpMethod.POST, "/v1/join/9876543210123456789", body = SAMPLE_OFFER)

        assertEquals(HttpResponseStatus.OK, reply.status)
        assertEquals("application/sdp", reply.contentType)
        assertEquals(answer.encode(), reply.body)
    }

    @Test
    fun `malformed offers are rejected`() {
        assertEquals(HttpResponseStatus.BAD_REQUEST, send(HttpMethod.POST, "/v1/join/1", body = "v=0\r\n").status)
    }

    @Test
    fun `unknown routes are not found`() {
        assertEquals(HttpResponseStatus.NOT_FOUND, send(HttpMethod.GET, "/").status)
        assertEquals(HttpResponseStatus.NOT_FOUND, send(HttpMethod.POST, "/v1/join").status)
        assertEquals(HttpResponseStatus.NOT_FOUND, send(HttpMethod.POST, "/v1/join/1/extra").status)
        assertEquals(HttpResponseStatus.NOT_FOUND, send(HttpMethod.GET, "/v1/join/9876543210123456789").status)
    }

    @Test
    fun `an https attempt is noted, and answered as before`() {
        val channel = EmbeddedChannel(HttpsAttemptDetector(), HttpServerCodec(), HttpObjectAggregator(65536), handler())
        // A TLS record header, then bytes the HTTP decoder can't make sense of, as a ClientHello looks to it.
        channel.writeInbound(Unpooled.wrappedBuffer(byteArrayOf(0x16, 0x03, 0x01) + "\u0002 hello there\r\n\r\n".toByteArray()))

        val response = channel.readOutbound<ByteBuf>()
        try {
            assertTrue(response.toString(UTF_8).startsWith("HTTP/1.1 400"))
            assertTrue(channel.attr(TRIED_HTTPS).get())
        } finally {
            response.release()
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `a client hanging up is logged quietly`() {
        val events = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(NetherNetSignalingHandler::class.java) as Logger
        logger.addAppender(events)
        val channel = EmbeddedChannel(handler())
        try {
            channel.pipeline().fireExceptionCaught(SocketException("Connection reset"))
        } finally {
            logger.detachAppender(events)
        }

        assertFalse(channel.isOpen)
        assertEquals(listOf(Level.DEBUG), events.list.map { it.level })
    }

    private fun handler() = NetherNetSignalingHandler(joinInfo = { info }, answerOffer = { _, _ -> answer })

    private data class Reply(val status: HttpResponseStatus, val contentType: String?, val body: String)

    private fun send(method: HttpMethod, uri: String, body: String = ""): Reply {
        val channel = EmbeddedChannel(handler())
        channel.writeInbound(DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri, Unpooled.copiedBuffer(body, UTF_8)))

        val response = channel.readOutbound<FullHttpResponse>()
        try {
            return Reply(response.status(), response.headers()[HttpHeaderNames.CONTENT_TYPE], response.content().toString(UTF_8))
        } finally {
            response.release()
            channel.finishAndReleaseAll()
        }
    }
}
