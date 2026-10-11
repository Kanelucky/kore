package org.koremc

import org.koremc.protocol.BiomeDefinitionList
import org.koremc.protocol.BlockDefinitions
import org.koremc.protocol.CreativeContent
import org.koremc.protocol.ItemRegistry
import org.koremc.protocol.ServerPacket
import org.koremc.registry.CreativeInventory
import org.koremc.registry.VanillaData
import org.koremc.world.FlatWorld

/**
 * What every session shares: the world players join, the game's
 * definitions they're sent as they join, encoded once, and the creative
 * inventory their requests are checked against.
 */
class GameData(
    val world: FlatWorld,
    /** What follows `StartGame`: the item registry first, then the rest of the game's definitions. */
    val definitions: List<ServerPacket>,
    /** The blocks defined in data, which go in `StartGame`. */
    val blockDefinitions: BlockDefinitions,
    val creative: CreativeInventory,
) {
    companion object {
        fun of(vanilla: VanillaData, world: FlatWorld) = GameData(
            world = world,
            definitions = listOf(ItemRegistry(vanilla.items), BiomeDefinitionList(vanilla.biomes), CreativeContent(vanilla.creative)),
            blockDefinitions = BlockDefinitions(vanilla.blockDefinitions),
            creative = vanilla.creative,
        )
    }
}
