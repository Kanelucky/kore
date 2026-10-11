package org.koremc.network

import kotlinx.serialization.Serializable

/**
 * Body of the `GET /v1/join` response. The client reads this to decide
 * whether the server speaks NetherNet and what to show on the server list.
 */
@Serializable
data class JoinInfo(
    val name: String,
    val protocol: Int,
    val version: String,
    val level: String,
    val players: Int,
    val maxPlayers: Int,
    /** 0 = Survival, 1 = Creative, 2 = Adventure. */
    val gameType: Int,
)
