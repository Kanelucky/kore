package org.koremc.auth

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

private val log = LoggerFactory.getLogger(AuthService::class.java)

/** The audience Minecraft's authorization service writes into multiplayer tokens. */
const val MULTIPLAYER_AUDIENCE = "api://auth-minecraft-services/multiplayer"

private const val DISCOVERY_URL = "https://client.discovery.minecraft-services.net/api/v1.0/discovery/MinecraftPE/builds/"
private val CLOCK_SKEW = 1.minutes
private val REFRESH_INTERVAL = 30.minutes
private val MIN_REFRESH_GAP = 1.minutes
private val HTTP_TIMEOUT = 10.seconds

/** What the authorization service publishes: the issuer its tokens name, the algorithms it signs with, and its keys by ID. */
class AuthKeys(val issuer: String, val algorithms: Set<String>, val keys: Map<String, PublicKey>)

/** A multiplayer token whose signature, issuer, audience and lifetime all checked out. */
data class MultiplayerToken(
    val displayName: String,
    /** Null for players who aren't signed in to Xbox Live. */
    val xuid: String?,
    /** The client's key, which signs its client data and its offer's fingerprints. */
    val publicKey: ECPublicKey,
    val playFabId: String?,
    /** Set for offline players, whose UUID can't be derived from an XUID. */
    val legacyUuid: String?,
)

/**
 * Checks multiplayer tokens against Minecraft's authorization service. It
 * never touches the network: [keys] returns a cached snapshot, and when the
 * snapshot is missing or lacks a token's key, [onMissingKeys] asks for a
 * refresh in the background.
 */
class MultiplayerTokenVerifier(
    private val keys: () -> AuthKeys?,
    private val clock: () -> Instant = Instant::now,
    private val onMissingKeys: () -> Unit = {},
) {
    fun verify(token: String): MultiplayerToken {
        val published = keys() ?: run {
            onMissingKeys()
            throw JoseException("Minecraft's authorization keys haven't been fetched")
        }
        val jws = Jws.parse(token)
        val algorithm = jws.algorithm?.takeIf { it in published.algorithms }
            ?: throw JoseException("token is signed with ${jws.algorithm}, which the authorization service doesn't use")
        val key = published.keys[jws.keyId] ?: run {
            onMissingKeys()
            throw JoseException("token is signed with unknown key ${jws.keyId}")
        }
        if (!jws.verify(key, algorithm)) throw JoseException("token signature doesn't verify")

        val claims = jws.claims
        if (claims.string("iss") != published.issuer) throw JoseException("token was issued by ${claims.string("iss")}")
        if (MULTIPLAYER_AUDIENCE !in audiences(claims)) throw JoseException("token isn't meant for multiplayer")
        checkLifetime(claims, clock(), requireExpiry = true)

        return MultiplayerToken(
            displayName = claims.string("xname") ?: throw JoseException("token has no display name"),
            xuid = claims.string("xid")?.takeIf { it.isNotEmpty() },
            publicKey = parseClientKey(claims["cpk"]),
            playFabId = claims.string("mid"),
            legacyUuid = claims.string("leguuid"),
        )
    }
}

/** Rejects a token that has expired or isn't valid yet, allowing a minute of clock skew either way. */
fun checkLifetime(claims: JsonObject, now: Instant, requireExpiry: Boolean) {
    val expiry = claims.epochSeconds("exp")
    if (expiry == null && requireExpiry) throw JoseException("token has no expiry")
    if (expiry != null && now - CLOCK_SKEW.toJavaDuration() > expiry) throw JoseException("token expired at $expiry")

    val notBefore = claims.epochSeconds("nbf")
    if (notBefore != null && now + CLOCK_SKEW.toJavaDuration() < notBefore) throw JoseException("token isn't valid until $notBefore")
}

/**
 * Finds Minecraft's authorization service and keeps its signing keys
 * cached. The lookup goes from service discovery for the game version, to
 * the service's OpenID configuration, to its key set. It runs on a
 * background thread at startup, every 30 minutes, and whenever a token
 * arrives signed with a key Kore hasn't seen.
 */
class AuthService(private val gameVersion: String) : AutoCloseable {
    @Volatile
    private var published: AuthKeys? = null

    @Volatile
    private var lastAttempt = Instant.EPOCH

    private val http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT.toJavaDuration()).build()
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "kore-auth").apply { isDaemon = true }
    }

    val verifier = MultiplayerTokenVerifier(keys = { published }, onMissingKeys = ::refreshSoon)

    /** Fetches the keys, waiting for the first attempt to finish, then keeps them fresh. */
    fun start() {
        executor.submit(::refresh).get()
        val interval = REFRESH_INTERVAL.inWholeSeconds
        executor.scheduleWithFixedDelay(::refresh, interval, interval, TimeUnit.SECONDS)
    }

    override fun close() {
        executor.shutdownNow()
    }

    private fun refreshSoon() {
        if (Instant.now() - MIN_REFRESH_GAP.toJavaDuration() >= lastAttempt) executor.execute(::refresh)
    }

    private fun refresh() {
        lastAttempt = Instant.now()
        try {
            val keys = fetch()
            published = keys
            log.debug("Loaded {} signing keys from Minecraft's authorization service ({})", keys.keys.size, keys.issuer)
        } catch (e: Exception) {
            log.warn("Couldn't load Minecraft's authorization keys: {}", e.message)
        }
    }

    private fun fetch(): AuthKeys {
        val discovery = getJson(DISCOVERY_URL + gameVersion)
        val issuer = discovery.at("result", "serviceEnvironments", "auth", "prod")?.string("issuer")
            ?: throw IOException("service discovery has no authorization environment")

        // The issuer's own configuration names the exact issuer string tokens carry, and where its keys live.
        val configuration = getJson(issuer.trimEnd('/') + "/.well-known/openid-configuration")
        val configuredIssuer = configuration.string("issuer")
        if (configuredIssuer == null || configuredIssuer.trimEnd('/') != issuer.trimEnd('/')) {
            throw IOException("OpenID configuration names issuer $configuredIssuer, not $issuer")
        }
        val algorithms = (configuration["id_token_signing_alg_values_supported"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet()
            ?: setOf("RS256")
        val keySetUrl = configuration.string("jwks_uri")?.takeIf { it.startsWith("https://") }
            ?: throw IOException("OpenID configuration has no HTTPS key set")

        val keys = (getJson(keySetUrl)["keys"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.string("kty") == "RSA" && (it.string("use") ?: "sig") == "sig" }
            .mapNotNull { jwk -> jwk.string("kid")?.let { it to rsaKeyFromJwk(jwk) } }
            .toMap()
        if (keys.isEmpty()) throw IOException("key set has no RSA signing keys")
        return AuthKeys(configuredIssuer, algorithms, keys)
    }

    private fun getJson(url: String): JsonObject {
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(HTTP_TIMEOUT.toJavaDuration())
            .header("Accept", "application/json")
            .header("User-Agent", "Kore")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw IOException("$url answered HTTP ${response.statusCode()}")
        return parseJsonObject(response.body())
    }
}

private fun audiences(claims: JsonObject): List<String> = when (val audience = claims["aud"]) {
    is JsonPrimitive -> listOfNotNull(audience.contentOrNull)
    is JsonArray -> audience.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    else -> emptyList()
}

private fun JsonObject.epochSeconds(key: String) = (this[key] as? JsonPrimitive)?.longOrNull?.let(Instant::ofEpochSecond)

private fun JsonObject.at(vararg path: String): JsonObject? =
    path.fold(this as JsonObject?) { node, key -> node?.get(key) as? JsonObject }
