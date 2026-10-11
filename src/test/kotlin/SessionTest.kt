package org.koremc

import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import org.koremc.auth.MultiplayerTokenVerifier
import org.koremc.auth.TestAuthority
import org.koremc.auth.XUID
import org.koremc.auth.ecKeyPair
import org.koremc.protocol.BatchCodec
import org.koremc.protocol.clientDataToken
import org.koremc.protocol.connectionRequest
import org.koremc.protocol.loginPacket
import org.koremc.protocol.tokenAuthentication
import org.koremc.protocol.writeVarUInt
import java.security.interfaces.ECPublicKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PROTOCOL = 2193

class SessionTest {
    private val authority = TestAuthority()
    private val client = ecKeyPair()
    private val clientKey = client.public as ECPublicKey
    private val authenticator = LoginAuthenticator(MultiplayerTokenVerifier(keys = { authority.keys }), onlineMode = true)

    private val sent = mutableListOf<ByteArray>()
    private val session = Session(PROTOCOL, networkId = "9876543210123456789", offerKey = clientKey, authenticator) { sent += it }

    /** The client's side of batching, which switches on compression when NetworkSettings arrives. */
    private val batches = BatchCodec()

    @Test
    fun `a matching protocol gets network settings`() {
        clientSends(requestNetworkSettings(PROTOCOL))

        assertContentEquals(bytes(0x8F, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00), receivedPacket())
    }

    @Test
    fun `older clients are told they're out of date`() {
        clientSends(requestNetworkSettings(PROTOCOL - 1))

        assertContentEquals(bytes(0x02, 0x00, 0x00, 0x00, 0x01), receivedPacket())
    }

    @Test
    fun `newer clients are told the server is out of date, and nothing more`() {
        clientSends(requestNetworkSettings(PROTOCOL + 1))
        clientSends(requestNetworkSettings(PROTOCOL))

        assertEquals(1, sent.size)
        assertContentEquals(bytes(0x02, 0x00, 0x00, 0x00, 0x02), receivedPacket())
    }

    @Test
    fun `an authenticated login sets the player`() {
        negotiateSettings()

        // The skin makes the batch big enough to be compressed, as real logins are.
        clientSends(login(authority.multiplayerToken(clientKey), skinData = "A".repeat(4096)))

        val player = session.player
        assertEquals("Steve", player?.displayName)
        assertEquals(XUID, player?.xuid)
        assertEquals(true, player?.verified)
        assertEquals("1.26.50", session.login?.clientInfo?.gameVersion)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a login that doesn't authenticate is disconnected`() {
        negotiateSettings()

        clientSends(login(TestAuthority().multiplayerToken(clientKey)))
        clientSends(login(authority.multiplayerToken(clientKey)))

        // Disconnect (5) with reason NotAuthenticated (46, zigzagged to 92) and no custom message.
        assertContentEquals(bytes(0x05, 0x5C, 0x01), receivedPacket())
        assertEquals(1, sent.size)
        assertNull(session.player)
    }

    @Test
    fun `packets out of order are ignored`() {
        clientSends(login(authority.multiplayerToken(clientKey)))

        assertNull(session.player)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a malformed batch doesn't derail the session`() {
        negotiateSettings()

        session.receive(bytes(0x05, 0x01))
        clientSends(login(authority.multiplayerToken(clientKey)))

        assertEquals("Steve", session.player?.displayName)
    }

    private fun negotiateSettings() {
        clientSends(requestNetworkSettings(PROTOCOL))
        sent.clear()
        batches.enableCompression(256)
    }

    private fun clientSends(packet: ByteArray) = session.receive(batches.encode(listOf(packet)))

    private fun receivedPacket() = batches.decode(sent.last()).single()

    private fun requestNetworkSettings(protocol: Int) =
        ByteBufUtil.getBytes(Unpooled.buffer().writeVarUInt(193).writeInt(protocol))

    private fun login(token: String, skinData: String = "") =
        loginPacket(PROTOCOL, connectionRequest(tokenAuthentication(token), clientDataToken(client.private, skinData)))

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
