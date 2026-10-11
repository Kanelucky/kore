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
import org.koremc.network.rtc.InvalidOfferException
import org.koremc.network.rtc.SdpAnswer
import org.koremc.network.rtc.SdpOffer
import org.koremc.network.rtc.parseOffer
import org.slf4j.LoggerFactory
import kotlin.text.Charsets.UTF_8

private val log = LoggerFactory.getLogger(NetherNetSignalingHandler::class.java)

private const val JOIN_PATH = "/v1/join"
private val OFFER_PATH = Regex("$JOIN_PATH/([^/]+)")
private val IDENTITY_LINE = Regex("(?m)^a=identity:.*$")

/**
 * Routes the client's NetherNet signaling requests:
 *
 * - `GET /v1/join` returns [JoinInfo] as JSON. A 2xx here is how the client
 *   learns the server supports NetherNet at all.
 * - `POST /v1/join/{networkId}` carries the client's SDP offer, answered by
 *   [answerOffer]. The client makes one attempt and never retries.
 *
 * Anything else gets a 404.
 */
@Sharable
class NetherNetSignalingHandler(
    private val joinInfo: () -> JoinInfo,
    private val answerOffer: (SdpOffer) -> SdpAnswer,
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

    private fun route(request: FullHttpRequest, path: String): FullHttpResponse {
        val offerPath = OFFER_PATH.matchEntire(path)
        return when {
            request.method() == HttpMethod.GET && path == JOIN_PATH ->
                response(HttpResponseStatus.OK, Json.encodeToString(joinInfo()), "application/json; charset=utf-8")

            request.method() == HttpMethod.POST && offerPath != null ->
                answer(networkId = offerPath.groupValues[1], sdp = request.content().toString(UTF_8))

            else -> textResponse(HttpResponseStatus.NOT_FOUND)
        }
    }

    private fun answer(networkId: String, sdp: String): FullHttpResponse {
        // The identity line carries the player's auth token, which has no business in a log file.
        log.debug("SDP offer from network {}:\n{}", networkId, sdp.replace(IDENTITY_LINE, "a=identity:<redacted>"))

        val offer = try {
            parseOffer(sdp)
        } catch (e: InvalidOfferException) {
            log.warn("Rejected offer from network {}: {}", networkId, e.message)
            return textResponse(HttpResponseStatus.BAD_REQUEST)
        }

        val answer = answerOffer(offer)
        log.info(
            "Answered network {} ({} candidates, {} identity)",
            networkId, offer.candidates.size, if (offer.identity != null) "with" else "no",
        )
        return response(HttpResponseStatus.OK, answer.encode(), "application/sdp")
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

private fun textResponse(status: HttpResponseStatus) =
    response(status, status.reasonPhrase(), "text/plain; charset=utf-8")

private fun response(status: HttpResponseStatus, body: String, contentType: String): FullHttpResponse =
    DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(body, UTF_8)).apply {
        headers().set(HttpHeaderNames.CONTENT_TYPE, contentType)
    }
