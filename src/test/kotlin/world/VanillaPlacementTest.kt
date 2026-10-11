package org.koremc.world

import org.koremc.registry.VanillaData
import org.koremc.registry.assumeGameData
import org.koremc.registry.gameData
import kotlin.test.Test
import kotlin.test.assertEquals

class VanillaPlacementTest {
    @Test
    fun `every block placed against every face, by a player facing every way, takes a state the game has`() {
        assumeGameData()
        val blocks = VanillaData.load(gameData).blocks
        val pos = BlockPos(0, 0, 0)
        // Beside the block, above it and below it, for blocks that face the player up or down.
        val eyes = listOf(Vec3(3.5f, 1.62f, 0.5f), Vec3(0.5f, 3.62f, 0.5f), Vec3(0.5f, -1.38f, 0.5f))

        val missing = blocks.states.map { it.name }.distinct().flatMap { name ->
            val block = blocks.defaultOf(name)!!
            Face.entries.flatMap { face ->
                AROUND.flatMap { facing ->
                    eyes.mapNotNull { at ->
                        val properties = placedProperties(block, pos, face, clickY = 0.25f, Placer(facing, eyes = at))
                        "$name against $face facing $facing: $properties".takeIf { blocks.find(name, properties) == null }
                    }
                }
            }
        }.distinct()

        assertEquals(emptyList(), missing.take(60), "${missing.size} placements without a state")
    }
}
