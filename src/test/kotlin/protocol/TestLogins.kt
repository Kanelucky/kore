package org.koremc.protocol

import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.koremc.auth.signJwt
import java.security.PrivateKey

/** The authentication JSON current clients send: a sign-in type, the legacy chain wrapped in a string, and the multiplayer token. */
internal fun tokenAuthentication(token: String, type: Int = 0, chain: List<String> = emptyList()) = buildJsonObject {
    put("AuthenticationType", type)
    put("Certificate", buildJsonObject { putJsonArray("chain") { chain.forEach { add(it) } } }.toString())
    put("Token", token)
}.toString()

/** Client data signed by [key], as the client signs it with the key its token names. */
internal fun clientDataToken(key: PrivateKey, skinData: String = "AAAA") = signJwt(
    buildJsonObject {
        put("GameVersion", "1.26.50")
        put("DeviceOS", 7)
        put("DeviceModel", "Desktop")
        put("LanguageCode", "en_GB")
        put("SkinData", skinData)
    },
    key,
    "ES384",
)

/** A connection request: two strings, each behind a little-endian int32 length. */
internal fun connectionRequest(authentication: String, clientDataToken: String): ByteArray {
    val auth = authentication.toByteArray()
    val clientData = clientDataToken.toByteArray()
    return ByteBufUtil.getBytes(
        Unpooled.buffer()
            .writeIntLE(auth.size).writeBytes(auth)
            .writeIntLE(clientData.size).writeBytes(clientData),
    )
}

internal fun loginPacket(protocol: Int, connectionRequest: ByteArray): ByteArray =
    ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(1).writeInt(protocol).writeByteArray(connectionRequest))
