package org.koremc.network

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.text.Charsets.UTF_8

private val log = LoggerFactory.getLogger(NetherNetSignalingHandler::class.java)

private const val JOIN_PATH = "/v1/join"

/**
 * Routes the client's NetherNet signaling requests:
 *
 * - `GET /v1/join` returns [JoinInfo] as JSON. A 2xx here is how the client
 *   learns the server supports NetherNet at all.
 * - `POST /v1/join/{networkId}` carries the client's SDP offer. Answering it
 *   is the next milestone, so for now it is logged and rejected with 501.
 *
 * Anything else gets a 404.
 */
@Sharable
class NetherNetSignalingHandler(
    private val joinInfo: () -> JoinInfo,
) : SimpleChannelInboundHandler<FullHttpRequest>() {

    override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val remote = ctx.channel().remoteAddress()

        if (request.decoderResult().isFailure) {
            log.warn("Malformed request from {}: {}", remote, request.decoderResult().cause().message)
            ctx.reply(request, textResponse(HttpResponseStatus.BAD_REQUEST))
            return
        }

        val path = QueryStringDecoder(request.uri()).path()
        log.info("{} {} from {} ({} bytes)", request.method(), path, remote, request.content().readableBytes())
        log.debug("Headers from {}: {}", remote, request.headers())

        ctx.reply(request, route(request, path))
    }

    private fun route(request: FullHttpRequest, path: String): FullHttpResponse = when {
        request.method() == HttpMethod.GET && path == JOIN_PATH ->
            jsonResponse(Json.encodeToString(joinInfo()))

        request.method() == HttpMethod.POST && path.startsWith("$JOIN_PATH/") -> {
            val networkId = path.removePrefix("$JOIN_PATH/")
            log.debug("SDP offer for network {}:\n{}", networkId, request.content().toString(UTF_8))
            textResponse(HttpResponseStatus.NOT_IMPLEMENTED)
        }

        else -> textResponse(HttpResponseStatus.NOT_FOUND)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.error("Signaling error on {}", ctx.channel().remoteAddress(), cause)
        ctx.close()
    }
}

private fun ChannelHandlerContext.reply(request: FullHttpRequest, response: FullHttpResponse) {
    val keepAlive = request.decoderResult().isSuccess && HttpUtil.isKeepAlive(request)
    HttpUtil.setContentLength(response, response.content().readableBytes().toLong())
    HttpUtil.setKeepAlive(response, keepAlive)

    val write = writeAndFlush(response)
    if (!keepAlive) write.addListener(ChannelFutureListener.CLOSE)
}

private fun jsonResponse(body: String) =
    response(HttpResponseStatus.OK, body, "application/json; charset=utf-8")

private fun textResponse(status: HttpResponseStatus) =
    response(status, status.reasonPhrase(), "text/plain; charset=utf-8")

private fun response(status: HttpResponseStatus, body: String, contentType: String): FullHttpResponse =
    DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(body, UTF_8)).apply {
        headers().set(HttpHeaderNames.CONTENT_TYPE, contentType)
    }
