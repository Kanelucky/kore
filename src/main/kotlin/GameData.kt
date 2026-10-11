package org.koremc

import org.koremc.protocol.BiomeDefinitionList
import org.koremc.protocol.BlockDefinitions
import org.koremc.protocol.CreativeContent
import org.koremc.protocol.ItemRegistry
import org.koremc.protocol.ServerPacket
import org.koremc.registry.CreativeInventory
import org.koremc.registry.Mining
import org.koremc.registry.VanillaData
import org.koremc.world.Drops
import org.koremc.world.FlatWorld

/**
 * What every session shares: the world players join, the game's
 * definitions they're sent as they join, encoded once, the creative
 * inventory their requests are checked against, and how blocks take to
 * mining.
 */
class GameData(
    val world: FlatWorld,
    /** What follows `StartGame`: the item registry first, then the rest of the game's definitions. */
    val definitions: List<ServerPacket>,
    /** The blocks defined in data, which go in `StartGame`. */
    val blockDefinitions: BlockDefinitions,
    val creative: CreativeInventory,
    /** How each block takes to mining, by block name, which sets how long survival players take to break it. */
    val mining: Map<String, Mining>,
    /** What blocks drop when survival players break them. */
    val drops: Drops,
) {
    companion object {
        fun of(vanilla: VanillaData, world: FlatWorld) = GameData(
            world = world,
            definitions = listOf(ItemRegistry(vanilla.items), BiomeDefinitionList(vanilla.biomes), CreativeContent(vanilla.creative)),
            blockDefinitions = BlockDefinitions(vanilla.blockDefinitions),
            creative = vanilla.creative,
            mining = vanilla.mining,
            drops = Drops(vanilla.items, vanilla.creative, vanilla.blocks),
        )
    }
}
