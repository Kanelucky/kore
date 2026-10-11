package org.koremc

import org.koremc.network.JoinInfo
import java.nio.file.Path

/**
 * Static server settings. Hardcoded defaults for now; this becomes the
 * target of a config file loader once there is something worth configuring.
 */
data class ServerConfig(
    /** TCP port for signaling and UDP port for gameplay. */
    val port: Int = 19132,
    /** Addresses to advertise as ICE candidates. Empty means every routable local address. */
    val advertisedAddresses: List<String> = emptyList(),
    /** Where the operator identity key lives. Clients pin it, so keep it safe and don't regenerate it. */
    val operatorKeyFile: Path = Path.of("operator.key"),
    /** Shown to players as untrusted text when they first accept this server's key. */
    val operatorDomain: String = "localhost",
    val name: String = "Kore Server",
    val level: String = "world",
    val maxPlayers: Int = 20,
    val gameType: Int = 0,
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
    gameType = gameType,
)
