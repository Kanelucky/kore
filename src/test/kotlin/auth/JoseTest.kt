package org.koremc.auth

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JoseTest {
    private val claims = buildJsonObject { put("sub", "player") }
    private val ec = ecKeyPair()
    private val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test
    fun `tokens verify with the right key and algorithm`() {
        assertTrue(Jws.parse(signJwt(claims, rsa.private, "RS256")).verify(rsa.public, "RS256"))
        assertTrue(Jws.parse(signJwt(claims, ec.private, "ES384")).verify(ec.public, "ES384"))
        assertEquals("player", Jws.parse(signJwt(claims, ec.private, "ES384")).claims.string("sub"))
    }

    @Test
    fun `tokens don't verify with another key`() {
        assertFalse(Jws.parse(signJwt(claims, ec.private, "ES384")).verify(ecKeyPair().public, "ES384"))
    }

    @Test
    fun `the verifier picks the algorithm, not the token`() {
        val es384 = Jws.parse(signJwt(claims, ec.private, "ES384"))
        val unsigned = Jws.parse(unsignedToken())

        assertFalse(es384.verify(ec.public, "RS256"))
        assertFalse(unsigned.verify(ec.public, "ES384"))
        assertFalse(unsigned.verify(ec.public, "none"))
    }

    @Test
    fun `tampered claims don't verify`() {
        val (header, _, signature) = signJwt(claims, ec.private, "ES384").split('.')
        val forged = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"admin"}""".toByteArray())

        assertFalse(Jws.parse("$header.$forged.$signature").verify(ec.public, "ES384"))
    }

    @Test
    fun `detached signatures verify against the payload the verifier rebuilds`() {
        val payload = """{"fingerprint":[]}"""
        val (header, _, signature) = signJwt(parseJsonObject(payload), ec.private, "ES384").split('.')

        assertTrue(Jws.parseDetached("$header..$signature", payload.toByteArray()).verify(ec.public, "ES384"))
        assertFalse(Jws.parseDetached("$header..$signature", "{}".toByteArray()).verify(ec.public, "ES384"))
    }

    @Test
    fun `client keys arrive as DER or as a JWK`() {
        val key = ec.public as ECPublicKey
        val jwk = buildJsonObject {
            put("kty", "EC")
            put("crv", "P-384")
            put("x", Base64.getUrlEncoder().withoutPadding().encodeToString(key.w.affineX.toByteArray().takeLast(48).toByteArray()))
            put("y", Base64.getUrlEncoder().withoutPadding().encodeToString(key.w.affineY.toByteArray().takeLast(48).toByteArray()))
        }

        assertTrue(sameKey(key, parseClientKey(JsonPrimitive(key.base64Der()))))
        assertTrue(sameKey(key, parseClientKey(jwk)))
    }

    @Test
    fun `malformed input is rejected`() {
        assertFailsWith<JoseException> { Jws.parse("not-a-token") }
        assertFailsWith<JoseException> { Jws.parse("!!.!!.!!") }
        assertFailsWith<JoseException> { Jws.parseDetached(signJwt(claims, ec.private, "ES384"), ByteArray(0)) }
        assertFailsWith<JoseException> { parseClientKey(null) }
        assertFailsWith<JoseException> { parseClientKey(JsonPrimitive("bm90IGEga2V5")) }
        assertFailsWith<JoseException> { parseClientKey(buildJsonObject { put("kty", "EC"); put("crv", "P-192") }) }
    }

    private fun unsignedToken(): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return "${encoder.encodeToString("""{"alg":"none"}""".toByteArray())}.${encoder.encodeToString(claims.toString().toByteArray())}."
    }
}
