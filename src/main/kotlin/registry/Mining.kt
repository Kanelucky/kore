package org.koremc.registry

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType

private val json = Json { ignoreUnknownKeys = true }

/** The kinds of tool that dig some blocks quicker, or that some blocks need to drop anything. */
enum class ToolKind { PICKAXE, AXE, SHOVEL, HOE, SWORD, SHEARS }

/**
 * How a block takes to mining: its hardness, which sets how long it takes,
 * the kind of tool that digs it quicker, and the least tier of that tool it
 * drops anything for, or null if a bare hand will do. Tiers count from 0:
 * wooden and golden, stone and copper, iron, diamond, then netherite. A
 * negative hardness means only creative players break the block.
 */
data class Mining(val hardness: Double, val tool: ToolKind? = null, val harvestTier: Int? = null) {
    val unbreakable get() = hardness < 0
}

/** An entry in minecraft-data's `blocks.json`. */
@Serializable
private class MinecraftDataBlock(
    val name: String,
    val hardness: Double? = null,
    /** The tool tags the block carries, like `mineable/pickaxe`, joined by semicolons. */
    val material: String? = null,
    /** The tools that harvest the block, by item ID, if only some do. */
    val harvestTools: Map<String, Boolean>? = null,
)

/**
 * The least tier of tool a block drops anything for, by how many tools
 * minecraft-data says harvest it. It names them by item IDs from Java
 * Edition's numbering, which Kore has no use for, but the count tells the
 * tier: all seven tiers (or eight tools, swords and shears, for cobwebs),
 * stone and better, iron and better, or diamond and better.
 */
private val TIER_BY_HARVEST_TOOLS = mapOf(7 to 0, 8 to 0, 5 to 1, 3 to 2, 2 to 3)

/**
 * Reads how each block takes to mining from minecraft-data's `blocks.json`,
 * by block name. Blocks only creative players break harvest with nothing,
 * so their empty lists of tools are let be.
 */
fun readMining(blocks: String): Map<String, Mining> = json.decodeFromString<List<MinecraftDataBlock>>(blocks).associate { block ->
    val hardness = block.hardness ?: error("minecraft-data gives ${block.name} no hardness")
    val tools = block.harvestTools?.size?.takeUnless { hardness < 0 && it == 0 }
    val tier = tools?.let { TIER_BY_HARVEST_TOOLS[it] ?: error("minecraft-data lists $it tools that harvest ${block.name}, which isn't a tier Kore knows") }
    "minecraft:${block.name}" to Mining(hardness, block.material?.let(::toolFor), tier)
}

/** The tool a block's tags in minecraft-data say digs it quicker. */
private fun toolFor(material: String): ToolKind? {
    val tags = material.split(';')
    tags.firstNotNullOfOrNull { tag -> ToolKind.entries.find { tag == "mineable/${it.name.lowercase()}" } }?.let { return it }
    return when {
        "incorrect_for_wooden_tool" in tags -> ToolKind.PICKAXE
        "coweb" in tags || "sword_instantly_mines" in tags -> ToolKind.SWORD
        "wool" in tags -> ToolKind.SHEARS
        else -> null
    }
}

/**
 * How the blocks defined in data take to mining, from their definitions:
 * a hardness in their `destructible_by_mining` component, a tag naming
 * the tool that digs them quicker, and whether they need it to drop.
 */
fun readDataDrivenMining(definitions: List<BlockDefinition>): Map<String, Mining> = definitions.associate { definition ->
    val hardness = definition.data.getCompound("components", NbtMap.EMPTY).getCompound("minecraft:destructible_by_mining", NbtMap.EMPTY).getFloat("value", 0f)
    val tags = definition.data.getList("blockTags", NbtType.STRING, emptyList())
    val tool = ToolKind.entries.find { "minecraft:is_${it.name.lowercase()}_item_destructible" in tags }
    val needsTool = definition.data.getCompound("vanilla_block_data", NbtMap.EMPTY).getBoolean("requires_correct_tool_for_drops", false)
    definition.name to Mining(hardness.toDouble(), tool, if (needsTool) 0 else null)
}

/**
 * Fills in the blocks of [palette] that [mining] lacks, which are those
 * newer than minecraft-data's table, from older blocks of their family:
 * poplar wood from spruce, leaves from oak leaves, and beds from the bed.
 */
fun withNewerBlocks(mining: Map<String, Mining>, palette: BlockPalette): Map<String, Mining> {
    val missing = palette.states.map { it.name }.distinct().filter { it !in mining }
    return mining + missing.mapNotNull { name ->
        val older = when {
            name.endsWith("_leaves") -> "minecraft:oak_leaves"
            "poplar" in name -> name.replace("poplar", "spruce")
            name.endsWith("_bed") -> "minecraft:bed"
            else -> null
        }
        older?.let(mining::get)?.let { name to it }
    }
}
