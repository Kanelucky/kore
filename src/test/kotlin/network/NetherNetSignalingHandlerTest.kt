package org.koremc.network

import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.text.Charsets.UTF_8

class NetherNetSignalingHandlerTest {
    private val info = JoinInfo(
        name = "Test Server",
        protocol = 1,
        version = "26.50",
        level = "world",
        players = 3,
        maxPlayers = 20,
        gameType = 1,
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
    fun `sdp offer is not implemented yet`() {
        val reply = send(HttpMethod.POST, "/v1/join/1234567890", body = "v=0\r\n")

        assertEquals(HttpResponseStatus.NOT_IMPLEMENTED, reply.status)
    }

    @Test
    fun `unknown routes are not found`() {
        assertEquals(HttpResponseStatus.NOT_FOUND, send(HttpMethod.GET, "/").status)
        assertEquals(HttpResponseStatus.NOT_FOUND, send(HttpMethod.POST, "/v1/join").status)
        assertEquals(HttpResponseStatus.NOT_FOUND, send(HttpMethod.GET, "/v1/join/1234567890").status)
    }

    private data class Reply(val status: HttpResponseStatus, val contentType: String?, val body: String)

    private fun send(method: HttpMethod, uri: String, body: String = ""): Reply {
        val channel = EmbeddedChannel(NetherNetSignalingHandler { info })
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
