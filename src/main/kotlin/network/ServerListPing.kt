package org.koremc.network

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import kotlin.text.Charsets.UTF_8

/** The bytes RakNet puts in every message sent outside a connection, which tell its pings apart from STUN and DTLS on the same port. */
private val OFFLINE_MAGIC = byteArrayOf(0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(), 0x12, 0x34, 0x56, 0x78)

// RakNet's messages from outside a connection that Kore answers: pings, and the start of a connection.
private const val UNCONNECTED_PING = 0x01
private const val UNCONNECTED_PING_OPEN_CONNECTIONS = 0x02
private const val OPEN_CONNECTION_REQUEST = 0x05
private const val UNCONNECTED_PONG = 0x1C
private const val INCOMPATIBLE_PROTOCOL = 0x19

/** Where a ping's magic is: after its ID and the client's time. */
private const val PING_MAGIC_AT = 9

/** The game modes, as the server list names them. */
private val GAME_MODE_NAMES = listOf("Survival", "Creative", "Adventure")

/**
 * Answers the game's server list, which still asks how a server is the
 * old RakNet way, on the same UDP port the game's played on: a ping
 * gets a pong with the server's name, version, players and level, which
 * is what the list shows, rather than "Locating server". A client
 * starting a RakNet connection, which Kore doesn't speak, hears its
 * version won't do, so it doesn't wait on an answer. The server is
 * [serverId] to RakNet, and on [port], and [status] says how it is now.
 */
class ServerListPing(private val serverId: Long, private val port: Int, private val status: () -> JoinInfo) {
    /** The answer to [datagram], if it's a ping or the start of a RakNet connection, or null for anything else. */
    fun answer(datagram: ByteBuf): ByteBuf? {
        val at = datagram.readerIndex()
        if (datagram.readableBytes() < 1) return null
        return when (datagram.getUnsignedByte(at).toInt()) {
            UNCONNECTED_PING, UNCONNECTED_PING_OPEN_CONNECTIONS -> {
                if (!datagram.hasMagic(at + PING_MAGIC_AT)) return null
                pong(time = datagram.getLong(at + 1))
            }
            OPEN_CONNECTION_REQUEST -> {
                if (!datagram.hasMagic(at + 1) || datagram.readableBytes() < 1 + OFFLINE_MAGIC.size + 1) return null
                val protocol = datagram.getUnsignedByte(at + 1 + OFFLINE_MAGIC.size).toInt()
                Unpooled.buffer().writeByte(INCOMPATIBLE_PROTOCOL).writeByte(protocol + 1).writeBytes(OFFLINE_MAGIC).writeLong(serverId)
            }
            else -> null
        }
    }

    private fun pong(time: Long): ByteBuf {
        val advertised = advertisement(status()).toByteArray(UTF_8)
        return Unpooled.buffer()
            .writeByte(UNCONNECTED_PONG)
            .writeLong(time)
            .writeLong(serverId)
            .writeBytes(OFFLINE_MAGIC)
            .writeShort(advertised.size)
            .writeBytes(advertised)
    }

    /**
     * How the server list reads a server, as the game's dedicated server
     * gives it: its edition, name, protocol, version, players, the most
     * players, its RakNet ID, its level, its game mode, then a 1 as the
     * dedicated server always gives, its ports for IPv4 and IPv6, and a 0.
     */
    internal fun advertisement(info: JoinInfo): String {
        fun field(text: String) = text.replace(';', ' ')
        val gameMode = GAME_MODE_NAMES.getOrElse(info.gameType) { GAME_MODE_NAMES[0] }
        return "MCPE;${field(info.name)};${info.protocol};${info.version};${info.players};${info.maxPlayers};" +
            "${serverId.toULong()};${field(info.level)};$gameMode;1;$port;${port + 1};0;"
    }

    private fun ByteBuf.hasMagic(index: Int): Boolean =
        writerIndex() >= index + OFFLINE_MAGIC.size && OFFLINE_MAGIC.indices.all { getByte(index + it) == OFFLINE_MAGIC[it] }
}
