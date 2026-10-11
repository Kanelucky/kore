package org.koremc.network.rtc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.math.BigInteger
import java.nio.file.Files
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.time.Instant
import java.util.Base64
import kotlin.io.path.deleteRecursively
import kotlin.io.path.ExperimentalPathApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Checks assertions the way the client does, per Mojang's NetherNet onboarding guide. */
@OptIn(ExperimentalPathApi::class)
class OperatorIdentityTest {
    private val directory = Files.createTempDirectory("kore-identity")
    private val keyFile = directory.resolve("operator.key")
    private val fingerprints = listOf(Fingerprint("sha-256", "11:22:33:44:55:66:77:88"))

    @AfterTest
    fun cleanUp() = directory.deleteRecursively()

    @Test
    fun `envelope names the operator domain and default protocol`() {
        val envelope = decodeEnvelope(OperatorIdentity.loadOrCreate(keyFile, "play.example.net", "Test").assert(fingerprints))
        val idp = envelope.getValue("idp").jsonObject

        assertEquals("play.example.net", idp.getValue("domain").jsonPrimitive.content)
        assertEquals("default", idp.getValue("protocol").jsonPrimitive.content)
    }

    @Test
    fun `token is self-signed with the embedded cpk`() {
        val now = Instant.ofEpochSecond(1_800_000_000)
        val assertion = assertionOf(OperatorIdentity.loadOrCreate(keyFile, "localhost", "Test").assert(fingerprints, now))

        val (header, payload, signature) = assertion.token.split('.')
        val claims = Json.parseToJsonElement(base64UrlDecode(payload).decodeToString()).jsonObject
        val cpk = claims.getValue("cpk").jsonObject

        assertTrue(verify("$header.$payload", signature, publicKeyOf(cpk)))
        assertEquals("P-384", cpk.getValue("crv").jsonPrimitive.content)
        assertEquals(now.epochSecond, claims.getValue("iat").jsonPrimitive.long)
        assertTrue(claims.getValue("exp").jsonPrimitive.long > now.epochSecond)
    }

    @Test
    fun `fingerprints are signed as a detached jws over canonical json`() {
        val assertion = assertionOf(OperatorIdentity.loadOrCreate(keyFile, "localhost", "Test").assert(fingerprints))

        val (header, payload, signature) = assertion.fingerprints.split('.')
        val canonical = """{"fingerprint":[{"algorithm":"sha-256","digest":"11:22:33:44:55:66:77:88"}]}"""
        val encodedCanonical = Base64.getUrlEncoder().withoutPadding().encodeToString(canonical.toByteArray())

        assertEquals("", payload, "payload must be detached")
        assertEquals(canonical, canonicalFingerprints(fingerprints))
        assertTrue(verify("$header.$encodedCanonical", signature, publicKeyOf(assertion.cpk)))
    }

    @Test
    fun `key survives a restart`() {
        val first = assertionOf(OperatorIdentity.loadOrCreate(keyFile, "localhost", "Test").assert(fingerprints))
        val second = assertionOf(OperatorIdentity.loadOrCreate(keyFile, "localhost", "Test").assert(fingerprints))

        assertEquals(first.cpk, second.cpk)
    }

    private class Assertion(val token: String, val fingerprints: String) {
        val cpk: JsonObject
            get() = Json.parseToJsonElement(base64UrlDecode(token.split('.')[1]).decodeToString())
                .jsonObject.getValue("cpk").jsonObject
    }

    private fun decodeEnvelope(identity: String) =
        Json.parseToJsonElement(Base64.getDecoder().decode(identity).decodeToString()).jsonObject

    private fun assertionOf(identity: String): Assertion {
        val assertion = Json.parseToJsonElement(decodeEnvelope(identity).getValue("assertion").jsonPrimitive.content).jsonObject
        return Assertion(
            token = assertion.getValue("token").jsonPrimitive.content,
            fingerprints = assertion.getValue("fingerprints").jsonPrimitive.content,
        )
    }

    private fun publicKeyOf(jwk: JsonObject): PublicKey {
        val params = AlgorithmParameters.getInstance("EC")
            .apply { init(ECGenParameterSpec("secp384r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(
            BigInteger(1, base64UrlDecode(jwk.getValue("x").jsonPrimitive.content)),
            BigInteger(1, base64UrlDecode(jwk.getValue("y").jsonPrimitive.content)),
        )
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
    }

    private fun verify(signingInput: String, signature: String, key: PublicKey) =
        Signature.getInstance("SHA384withECDSAinP1363Format").run {
            initVerify(key)
            update(signingInput.toByteArray())
            verify(base64UrlDecode(signature))
        }
}

private fun base64UrlDecode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
