package org.koremc.world

data class Vec3(val x: Float, val y: Float, val z: Float)

data class BlockPos(val x: Int, val y: Int, val z: Int) {
    /** The chunk column this block is in. */
    val chunk get() = ChunkPos(x shr 4, z shr 4)
}

/** A chunk column's position: the block coordinates of its corner, divided by 16. */
data class ChunkPos(val x: Int, val z: Int)
