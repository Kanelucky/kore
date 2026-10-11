package org.koremc

import org.koremc.network.JoinInfo

/**
 * Static server settings. Hardcoded defaults for now; this becomes the
 * target of a config file loader once there is something worth configuring.
 */
data class ServerConfig(
    val port: Int = 19132,
    val name: String = "Kore Server",
    val level: String = "world",
    val maxPlayers: Int = 20,
    val gameType: Int = 0,
    // Must match the client's protocol number and version string.
    val protocol: Int = 2193,
    val version: String = "26.50",
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
