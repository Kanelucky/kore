package org.koremc

import org.koremc.network.DisconnectReason
import org.koremc.network.NetherNetConnection
import org.koremc.protocol.BatchCodec
import org.koremc.protocol.ClientPacket
import org.koremc.protocol.Disconnect
import org.koremc.protocol.Login
import org.koremc.protocol.LoginRequest
import org.koremc.protocol.NetworkSettings
import org.koremc.protocol.PacketException
import org.koremc.protocol.PlayStatus
import org.koremc.protocol.RequestNetworkSettings
import org.koremc.protocol.ServerPacket
import org.koremc.protocol.decodeClientPacket
import org.koremc.protocol.encode
import org.koremc.protocol.parseConnectionRequest
import org.slf4j.LoggerFactory
import java.security.interfaces.ECPublicKey

private val log = LoggerFactory.getLogger(Session::class.java)

/** Batches this size or larger are compressed. Small ones cost more to compress than they save. */
private const val COMPRESSION_THRESHOLD = 256

/**
 * One player's protocol session. It owns the batch codec, so it decides
 * when compression switches on, and walks the login sequence one packet
 * at a time:
 *
 * 1. `RequestNetworkSettings` names the client's protocol. A mismatch gets a
 *    failed `PlayStatus`; a match gets `NetworkSettings`, after which every
 *    batch is compressed.
 * 2. `Login` carries the client's identity and client data, which must
 *    authenticate. A login that doesn't gets a `Disconnect`.
 *
 * Runs on the UDP event loop, with the connection callbacks that drive it.
 */
class Session(
    private val protocol: Int,
    private val networkId: String,
    /** The key the client proved when it connected, which its login must match. */
    private val offerKey: ECPublicKey?,
    private val authenticator: LoginAuthenticator,
    private val send: (ByteArray) -> Unit,
) {
    private enum class State { AWAITING_NETWORK_SETTINGS_REQUEST, AWAITING_LOGIN, AUTHENTICATED, REJECTED }

    private val batches = BatchCodec()
    private var state = State.AWAITING_NETWORK_SETTINGS_REQUEST

    /** The player, once their login has authenticated. */
    var player: PlayerIdentity? = null
        private set

    /** The authenticated login, kept for its client data: settings, skin and so on, which only the player's key vouches for. */
    var login: LoginRequest? = null
        private set

    fun receive(message: ByteArray) {
        val packets = try {
            batches.decode(message).map(::decodeClientPacket)
        } catch (e: PacketException) {
            log.warn("Dropped a batch from network {}: {}", networkId, e.message)
            return
        }
        packets.forEach(::handle)
    }

    private fun handle(packet: ClientPacket) {
        when (state) {
            State.AWAITING_NETWORK_SETTINGS_REQUEST -> if (packet is RequestNetworkSettings) onRequestNetworkSettings(packet) else unexpected(packet)
            State.AWAITING_LOGIN -> if (packet is Login) onLogin(packet) else unexpected(packet)
            State.AUTHENTICATED, State.REJECTED -> unexpected(packet)
        }
    }

    private fun onRequestNetworkSettings(packet: RequestNetworkSettings) {
        if (!acceptsProtocol(packet.protocol)) return

        // NetworkSettings itself goes out uncompressed. Compression starts with the next batch, both ways.
        send(NetworkSettings(compressionThreshold = COMPRESSION_THRESHOLD))
        batches.enableCompression(COMPRESSION_THRESHOLD)
        state = State.AWAITING_LOGIN
    }

    private fun onLogin(packet: Login) {
        if (!acceptsProtocol(packet.protocol)) return

        val request = try {
            parseConnectionRequest(packet.connectionRequest)
        } catch (e: PacketException) {
            log.warn("Network {} sent a login Kore can't read: {}", networkId, e.message)
            reject()
            return
        }

        val player = try {
            authenticator.authenticate(request, offerKey)
        } catch (e: AuthenticationException) {
            // The reason stays in the log. The player only learns that they couldn't be verified.
            log.info("Rejected the login from network {}, claiming to be {}: {}", networkId, request.identity.displayName, e.message)
            reject()
            return
        }
        this.player = player
        login = request
        state = State.AUTHENTICATED

        val client = request.clientInfo
        log.info(
            "{} ({}, {}) authenticated on network {}{}: {} {}, OS {}, {}",
            player.displayName, player.xuid ?: "no XUID", player.uuid, networkId,
            if (player.verified) "" else " without verification (offline mode)",
            request.authenticationType ?: "unknown sign-in", client.gameVersion, client.deviceOs, client.languageCode,
        )
    }

    private fun reject() {
        send(Disconnect(Disconnect.Reason.NOT_AUTHENTICATED))
        state = State.REJECTED
    }

    /** Turns away clients on another protocol, telling them whose version is behind. */
    private fun acceptsProtocol(clientProtocol: Int): Boolean {
        if (clientProtocol == protocol) return true

        log.info("Network {} speaks protocol {}, but Kore speaks {}", networkId, clientProtocol, protocol)
        val status = if (clientProtocol < protocol) PlayStatus.Status.LOGIN_FAILED_CLIENT_OLD else PlayStatus.Status.LOGIN_FAILED_SERVER_OLD
        send(PlayStatus(status))
        state = State.REJECTED
        return false
    }

    private fun unexpected(packet: ClientPacket) {
        log.debug("Ignored {} from network {} while {}", packet, networkId, state)
    }

    private fun send(packet: ServerPacket) = send(batches.encode(listOf(packet.encode())))
}

/** Every connected player's session, driven by the transport's connection callbacks on the UDP event loop. */
class Sessions(private val protocol: Int, private val authenticator: LoginAuthenticator) {
    private val sessions = HashMap<NetherNetConnection, Session>()

    fun connect(connection: NetherNetConnection) {
        sessions[connection] = Session(protocol, connection.networkId, connection.clientKey, authenticator) { message -> connection.send(message) }
    }

    fun receive(connection: NetherNetConnection, message: ByteArray) {
        sessions[connection]?.receive(message)
    }

    fun disconnect(connection: NetherNetConnection, reason: DisconnectReason) {
        sessions.remove(connection)
        log.debug("Session for network {} ended: {}", connection.networkId, reason)
    }
}
