package org.koremc.registry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType
import org.cloudburstmc.nbt.NbtUtils
import java.util.Base64
import java.util.UUID

/** The aux value an ingredient takes to mean any variant of its item. */
const val ANY_AUX = 32767

/** What a recipe takes in one place, and how many of it. */
sealed interface Ingredient {
    val count: Int

    /** An item, in one variant, or any if [aux] is null. */
    data class Item(val name: String, val aux: Int?, override val count: Int) : Ingredient

    /** Any item that [tag] takes in, like any planks. */
    data class Tag(val tag: String, override val count: Int) : Ingredient
}

/**
 * A recipe as the client knows it, by the number it calls it by in item
 * stack requests. The [block] says where it's made: `crafting_table` for
 * the crafting grids, `furnace`, `stonecutter` and so on.
 */
sealed interface Recipe {
    val networkId: Int
}

/** Ingredients in any arrangement, like dye from a flower. */
data class ShapelessRecipe(
    val id: String,
    val inputs: List<Ingredient>,
    val outputs: List<ItemStack>,
    val block: String,
    val priority: Int,
    val uuid: UUID,
    /** Whether what comes out keeps the data of what went in, as a dyed shulker box keeps its items. */
    val keepsData: Boolean,
    override val networkId: Int,
) : Recipe

/**
 * Ingredients in a pattern [width] by [height], row by row, with nothing in
 * the places that are null. The pattern can go anywhere in the grid, and
 * be mirrored if it [assumes symmetry][assumeSymmetry].
 */
data class ShapedRecipe(
    val id: String,
    val width: Int,
    val height: Int,
    val inputs: List<Ingredient?>,
    val outputs: List<ItemStack>,
    val block: String,
    val priority: Int,
    val assumeSymmetry: Boolean,
    val uuid: UUID,
    override val networkId: Int,
) : Recipe

/** A recipe the game works out itself, like copying a map, which the client knows by [uuid]. */
data class MultiRecipe(val uuid: UUID, override val networkId: Int) : Recipe

/** Upgrading a tool or armour with a smithing template, like diamond to netherite. */
data class SmithingTransformRecipe(
    val id: String,
    val template: Ingredient,
    val base: Ingredient,
    val addition: Ingredient,
    val output: ItemStack,
    val block: String,
    override val networkId: Int,
) : Recipe

/** Trimming armour with a smithing template. */
data class SmithingTrimRecipe(
    val id: String,
    val template: Ingredient,
    val base: Ingredient,
    val addition: Ingredient,
    val block: String,
    override val networkId: Int,
) : Recipe

/** Brewing a potion into another with a reagent, each by item and variant. */
data class PotionMix(val input: ItemStack, val reagent: ItemStack, val output: ItemStack)

/** Brewing a potion into another kind of bottle, like splash potions with gunpowder. */
data class ContainerMix(val input: ItemDefinition, val reagent: ItemDefinition, val output: ItemDefinition)

/** Where crafting grid recipes are made. */
const val CRAFTING_TABLE = "crafting_table"

/**
 * Every recipe, numbered from 1 in the order the game's data lists them,
 * and brewing's mixes. Ingredients can name an item tag, which takes in
 * the items [tags] lists under it.
 */
class Recipes(
    val recipes: List<Recipe>,
    val potionMixes: List<PotionMix>,
    val containerMixes: List<ContainerMix>,
    private val tags: Map<String, Set<String>>,
) {
    private val byNetworkId = recipes.associateBy { it.networkId }

    /** A number past every recipe's, from which to number other things the client crafts, like an enchanting table's offers. */
    val nextNetworkId = (recipes.maxOfOrNull { it.networkId } ?: 0) + 1

    operator fun get(networkId: Int): Recipe? = byNetworkId[networkId]

    /** Whether [stack] is what [ingredient] takes, setting aside how many. */
    fun accepts(ingredient: Ingredient, stack: ItemStack): Boolean = when (ingredient) {
        is Ingredient.Item -> stack.item.name == ingredient.name && (ingredient.aux == null || stack.aux == ingredient.aux)
        is Ingredient.Tag -> stack.item.name in tags[ingredient.tag].orEmpty()
    }

    /** The items [tag] takes in. */
    fun tagged(tag: String): Set<String> = tags[tag].orEmpty()

    /** The recipes of each block that cooks one item into another, like a furnace, by the item they take. */
    private val cooked: Map<String, Map<String, List<ShapelessRecipe>>> = recipes.filterIsInstance<ShapelessRecipe>()
        .filter { it.inputs.size == 1 && it.inputs[0] is Ingredient.Item }
        .groupBy { it.block }
        .mapValues { (_, recipes) -> recipes.groupBy { (it.inputs[0] as Ingredient.Item).name } }

    /** What [block], like `furnace` or `smoker`, makes of one of [stack], or null if it makes nothing of it. */
    fun cook(block: String, stack: ItemStack): ItemStack? =
        cooked[block]?.get(stack.item.name)?.firstOrNull { accepts(it.inputs[0], stack) }?.outputs?.firstOrNull()

    /**
     * What brewing [reagent] into [bottle] makes of it, or null if nothing:
     * a potion of another kind, or the same potion in another kind of
     * bottle, like a splash potion.
     */
    fun brew(bottle: ItemStack, reagent: ItemStack): ItemStack? {
        potionMixes.find { it.input.item == bottle.item && it.input.aux == bottle.aux && it.reagent.item == reagent.item && it.reagent.aux == reagent.aux }
            ?.let { return it.output.copy(count = 1) }
        return containerMixes.find { it.input == bottle.item && it.reagent == reagent.item }?.let { ItemStack(it.output, aux = bottle.aux) }
    }

    /** Whether brewing takes [stack] as a reagent, to brew into something. */
    fun isReagent(stack: ItemStack) = potionMixes.any { it.reagent.item == stack.item } || containerMixes.any { it.reagent == stack.item }

    companion object {
        val NONE = Recipes(emptyList(), emptyList(), emptyList(), emptyMap())
    }
}

private val json = Json { ignoreUnknownKeys = true }

/**
 * Reads the recipes in CloudburstMC's `recipes.json`, making their outputs
 * from [items] and their tagged ingredients' items from [tags]. A recipe
 * that makes an item [items] doesn't have fails here, on start.
 */
fun readRecipes(recipes: String, items: ItemStacks, blocks: BlockPalette, tags: Map<String, Set<String>>): Recipes {
    val file = json.parseToJsonElement(recipes).jsonObject
    var networkId = 0
    val read = file.getValue("recipes").jsonArray.map { element ->
        val recipe = element.jsonObject
        networkId++
        val id = recipe["id"]?.jsonPrimitive?.content.orEmpty()
        val block = recipe["block"]?.jsonPrimitive?.content.orEmpty()
        val priority = recipe["priority"]?.jsonPrimitive?.intOrNull ?: 0
        fun outputs() = recipe.getValue("output").let { if (it is JsonArray) it else JsonArray(listOf(it)) }.map { readOutput(it.jsonObject, items, blocks, id) }
        when (val type = recipe.getValue("type").jsonPrimitive.int) {
            SHAPELESS, USER_DATA_SHAPELESS -> ShapelessRecipe(
                id, recipe.getValue("input").jsonArray.map { readIngredient(it.jsonObject) }, outputs(), block, priority,
                recipeUuid(id), keepsData = type == USER_DATA_SHAPELESS, networkId,
            )
            SHAPED -> {
                val shape = recipe.getValue("shape").jsonArray.map { it.jsonPrimitive.content }
                val keys = recipe.getValue("input").jsonObject.mapValues { (_, ingredient) -> readIngredient(ingredient.jsonObject) }
                val width = shape.maxOf { it.length }
                val inputs = shape.flatMap { row -> (0 until width).map { column -> row.getOrNull(column)?.takeIf { it != ' ' }?.let { keys[it.toString()] } } }
                ShapedRecipe(
                    id, width, shape.size, inputs, outputs(), block, priority,
                    recipe["assumeSymetry"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true, recipeUuid(id), networkId,
                )
            }
            MULTI -> MultiRecipe(UUID.fromString(recipe.getValue("uuid").jsonPrimitive.content), networkId)
            SMITHING_TRANSFORM, SMITHING_TRIM -> {
                val input = recipe.getValue("input").jsonObject
                val template = readIngredient(input.getValue("template").jsonObject)
                val base = readIngredient(input.getValue("base").jsonObject)
                val addition = readIngredient(input.getValue("addition").jsonObject)
                // The data gives smithing recipes no names, which the client tells recipes apart by, so they get the game's.
                if (type == SMITHING_TRIM) {
                    SmithingTrimRecipe(id.ifEmpty { "minecraft:smithing_armor_trim" }, template, base, addition, block, networkId)
                } else {
                    val output = readOutput(recipe.getValue("output").jsonObject, items, blocks, id)
                    SmithingTransformRecipe(id.ifEmpty { "minecraft:smithing_${output.item.name.substringAfter(':')}" }, template, base, addition, output, block, networkId)
                }
            }
            else -> error("Recipe $id is of a type Kore doesn't know, $type")
        }
    }
    fun item(name: String) = items[name] ?: error("A brewing mix takes $name, which isn't in the item list")
    val potionMixes = file["potionMixes"]?.jsonArray.orEmpty().map { mix ->
        val fields = mix.jsonObject
        fun stack(id: String, meta: String) = ItemStack(item(fields.getValue(id).jsonPrimitive.content), aux = fields.getValue(meta).jsonPrimitive.int)
        PotionMix(stack("inputId", "inputMeta"), stack("reagentId", "reagentMeta"), stack("outputId", "outputMeta"))
    }
    val containerMixes = file["containerMixes"]?.jsonArray.orEmpty().map { mix ->
        val fields = mix.jsonObject
        ContainerMix(item(fields.getValue("inputId").jsonPrimitive.content), item(fields.getValue("reagentId").jsonPrimitive.content), item(fields.getValue("outputId").jsonPrimitive.content))
    }
    return Recipes(read, potionMixes, containerMixes, tags)
}

// Recipe types, as the game's data numbers them.
private const val SHAPELESS = 0
private const val SHAPED = 1
private const val MULTI = 4
private const val USER_DATA_SHAPELESS = 5
private const val SMITHING_TRANSFORM = 8
private const val SMITHING_TRIM = 9

/** A UUID for the recipe called [id], the same every time, as the data gives crafting recipes none. */
private fun recipeUuid(id: String) = UUID.nameUUIDFromBytes(id.toByteArray())

private fun readIngredient(ingredient: JsonObject): Ingredient {
    val count = ingredient["count"]?.jsonPrimitive?.intOrNull ?: 1
    return when (val type = ingredient.getValue("type").jsonPrimitive.content) {
        "default" -> {
            val aux = ingredient["auxValue"]?.jsonPrimitive?.intOrNull ?: ANY_AUX
            Ingredient.Item(ingredient.getValue("id").jsonPrimitive.content, aux.takeIf { it != ANY_AUX }, count)
        }
        "item_tag" -> Ingredient.Tag(ingredient.getValue("itemTag").jsonPrimitive.content, count)
        else -> error("An ingredient is of a type Kore doesn't know, $type")
    }
}

/** A recipe's output: its item, how many, its variant and its NBT, as little-endian NBT in base64. */
private fun readOutput(output: JsonObject, items: ItemStacks, blocks: BlockPalette, recipe: String): ItemStack {
    val name = output.getValue("id").jsonPrimitive.content
    val stack = items.of(name, output["count"]?.jsonPrimitive?.intOrNull ?: 1, output["damage"]?.jsonPrimitive?.intOrNull ?: 0)
        ?: error("Recipe $recipe makes $name, which isn't in the item list")
    val nbt = output["nbt_b64"]?.jsonPrimitive?.content?.let { base64 ->
        NbtUtils.createReaderLE(Base64.getDecoder().decode(base64).inputStream()).readTag() as? NbtMap
    }
    val block = output["block_state_b64"]?.jsonPrimitive?.content?.let { base64 ->
        (NbtUtils.createReaderLE(Base64.getDecoder().decode(base64).inputStream()).readTag() as? NbtMap)?.let { blocks.find(it.getString("name"), it.getCompound("states")) }
    }
    return stack.copy(nbt = nbt ?: NbtMap.EMPTY, block = block ?: stack.block)
}

/**
 * Reads BedrockData's `item_tags.json`, which lists each tag's items, and
 * adds the tags that items with components name themselves. Items newer
 * than the list take the tags of older ones of their family: poplar wood
 * spruce's, as in mining. The mushrooms a stew takes became a tag after
 * the list, which the shelf mushroom names itself; the red and brown
 * mushrooms were always in it.
 */
fun readItemTags(itemTags: String, items: List<ItemDefinition>): Map<String, Set<String>> {
    val tags = HashMap<String, MutableSet<String>>()
    json.decodeFromString<Map<String, List<String>>>(itemTags).forEach { (tag, members) -> tags.getOrPut(tag) { HashSet() } += members }
    for (item in items) {
        val named = item.components.getCompound("components", NbtMap.EMPTY).getList("item_tags", NbtType.STRING, emptyList())
        named.forEach { tags.getOrPut(it) { HashSet() } += item.name }
    }
    val tagsOf = HashMap<String, MutableList<String>>()
    tags.forEach { (tag, members) -> members.forEach { tagsOf.getOrPut(it) { ArrayList() } += tag } }
    for (item in items) {
        if (item.name in tagsOf || "poplar" !in item.name) continue
        tagsOf[item.name.replace("poplar", "spruce")]?.forEach { tags.getValue(it) += item.name }
    }
    tags.getOrPut(MUSHROOMS_FOR_STEW) { HashSet() } += listOf("minecraft:red_mushroom", "minecraft:brown_mushroom")
    return tags
}

private const val MUSHROOMS_FOR_STEW = "minecraft:mushrooms_for_stew"

/** Reads an element that may be missing. */
private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
