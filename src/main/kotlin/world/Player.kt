package org.koremc.world

/** How far above their feet a player's eyes are, which is the position Bedrock gives for them. */
const val PLAYER_EYE_HEIGHT = 1.62f

/** A standing player's size in blocks: the box they collide with. */
const val PLAYER_WIDTH = 0.6f
const val PLAYER_HEIGHT = 1.8f

/** A sneaking player's height, low enough to fit under a gap a block and a half high. */
const val PLAYER_SNEAKING_HEIGHT = 1.49f

/** How long a player can hold their breath, in ticks: 15 seconds. */
const val PLAYER_MAX_AIR: Short = 300

/** How far a player walks in a tick, before sprinting or effects change it. */
const val PLAYER_WALK_SPEED = 0.1f
