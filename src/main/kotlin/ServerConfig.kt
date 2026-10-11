package org.koremc

import org.koremc.network.JoinInfo
import org.koremc.protocol.BlockPos
import org.koremc.protocol.Difficulty
import org.koremc.protocol.GameMode
import java.nio.file.Path

/**
 * Static server settings. Hardcoded defaults for now; this becomes the
 * target of a config file loader once there is something worth configuring.
 */
data class ServerConfig(
    /** TCP port for signaling and UDP port for gameplay. */
    val port: Int = 19132,
    /**
     * Whether players must prove who they are through Xbox Live. Turn this
     * off only for local testing: offline mode admits anyone as anyone.
     */
    val onlineMode: Boolean = true,
    /** Addresses to advertise as ICE candidates. Empty means every routable local address. */
    val advertisedAddresses: List<String> = emptyList(),
    /** Where the operator identity key lives. Clients pin it, so keep it safe and don't regenerate it. */
    val operatorKeyFile: Path = Path.of("operator.key"),
    /** Shown to players as untrusted text when they first accept this server's key. */
    val operatorDomain: String = "localhost",
    /** Where the game's item and block data lives: a checkout of https://github.com/KoreMC/data matching [version]. */
    val dataDirectory: Path = Path.of("data"),
    val name: String = "Kore Server",
    val level: String = "world",
    val maxPlayers: Int = 20,
    val gameMode: GameMode = GameMode.SURVIVAL,
    val difficulty: Difficulty = Difficulty.NORMAL,
    val seed: Long = 0,
    /** Where new players appear: the block their feet stand in. */
    val spawn: BlockPos = BlockPos(0, 64, 0),
    /** The most chunks around each player Kore will send, whatever the client asks for. */
    val viewDistance: Int = 8,
    // Must match the client's protocol number and version string.
    val protocol: Int = 2193,
    val version: String = "1.26.50",
)

fun ServerConfig.toJoinInfo(onlinePlayers: Int) = JoinInfo(
    name = name,
    protocol = protocol,
    version = version,
    level = level,
    players = onlinePlayers,
    maxPlayers = maxPlayers,
    gameType = gameMode.id,
)
