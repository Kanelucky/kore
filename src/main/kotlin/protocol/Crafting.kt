package org.koremc.protocol

import io.netty.buffer.ByteBuf
import org.koremc.registry.ANY_AUX
import org.koremc.registry.Ingredient
import org.koremc.registry.MultiRecipe
import org.koremc.registry.Recipes
import org.koremc.registry.ShapedRecipe
import org.koremc.registry.ShapelessRecipe
import org.koremc.registry.SmithingTransformRecipe
import org.koremc.registry.SmithingTrimRecipe

private const val CRAFTING_DATA = 52

/**
 * Every recipe, for the client to show in its recipe book and to predict
 * what crafting makes, and brewing's mixes. Since 1.26.40, each kind of
 * recipe goes in a list of its own. It's the same for every player, so
 * it's encoded once.
 */
class CraftingData(val recipes: Recipes) : ServerPacket {
    override val id get() = CRAFTING_DATA

    private val body = encodeOnce {
        val all = recipes.recipes
        val shapeless = all.filterIsInstance<ShapelessRecipe>()
        writeList(all.filterIsInstance<ShapedRecipe>()) { writeShaped(it) }
        writeList(shapeless.filter { !it.keepsData }) { writeShapeless(it) }
        writeList(all.filterIsInstance<MultiRecipe>()) { writeUuid(it.uuid).writeVarUInt(it.networkId) }
        writeList(shapeless.filter { it.keepsData }) { writeShapeless(it) }
        writeVarUInt(0) // Shapeless chemistry recipes, which are Education Edition's
        writeVarUInt(0) // Shaped chemistry recipes
        writeList(all.filterIsInstance<SmithingTransformRecipe>()) { recipe ->
            writeString(recipe.id)
            writeIngredient(recipe.template).writeIngredient(recipe.base).writeIngredient(recipe.addition)
            writeItem(recipe.output)
            writeString(recipe.block)
            writeVarUInt(recipe.networkId)
        }
        writeList(all.filterIsInstance<SmithingTrimRecipe>()) { recipe ->
            writeString(recipe.id)
            writeIngredient(recipe.template).writeIngredient(recipe.base).writeIngredient(recipe.addition)
            writeString(recipe.block)
            writeVarUInt(recipe.networkId)
        }
        writeList(recipes.potionMixes) { mix ->
            writeVarInt(mix.input.item.runtimeId).writeVarInt(mix.input.aux)
            writeVarInt(mix.reagent.item.runtimeId).writeVarInt(mix.reagent.aux)
            writeVarInt(mix.output.item.runtimeId).writeVarInt(mix.output.aux)
        }
        writeList(recipes.containerMixes) { mix ->
            writeVarInt(mix.input.runtimeId).writeVarInt(mix.reagent.runtimeId).writeVarInt(mix.output.runtimeId)
        }
        writeVarUInt(0) // Material reducers, which are Education Edition's
        writeBoolean(true) // Clear any recipes the client had
    }

    override fun encodeBody(out: ByteBuf) {
        out.writeBytes(body)
    }

    override fun toString() = "CraftingData(${recipes.recipes.size} recipes)"
}

private fun <T> ByteBuf.writeList(items: List<T>, write: ByteBuf.(T) -> Unit) {
    writeVarUInt(items.size)
    items.forEach { write(it) }
}

private fun ByteBuf.writeShaped(recipe: ShapedRecipe) {
    writeString(recipe.id)
    writeVarInt(recipe.width)
    writeVarInt(recipe.height)
    writeList(recipe.inputs) { writeIngredient(it) }
    writeList(recipe.outputs) { writeItem(it) }
    writeUuid(recipe.uuid)
    writeString(recipe.block)
    writeVarInt(recipe.priority)
    writeBoolean(recipe.assumeSymmetry)
    writeBoolean(false) // An unlocking requirement, which the data doesn't give
    writeVarUInt(recipe.networkId)
}

private fun ByteBuf.writeShapeless(recipe: ShapelessRecipe) {
    writeString(recipe.id)
    writeList(recipe.inputs) { writeIngredient(it) }
    writeList(recipe.outputs) { writeItem(it) }
    writeUuid(recipe.uuid)
    writeString(recipe.block)
    writeVarInt(recipe.priority)
    writeBoolean(false) // An unlocking requirement
    writeVarUInt(recipe.networkId)
}

/**
 * An ingredient as the client reads it since 1.26.40: whether there is one,
 * then which kind, by name, the kind's own fields, and how many. An empty
 * one, or a tag, carries an aux value too.
 */
private fun ByteBuf.writeIngredient(ingredient: Ingredient?): ByteBuf {
    when (ingredient) {
        null -> return writeVarUInt(EMPTY_DESCRIPTOR).writeVarInt(ANY_AUX).writeVarInt(0)
        is Ingredient.Item -> writeVarUInt(DESCRIPTOR).writeString("name").writeString(ingredient.name).writeVarInt(ingredient.aux ?: ANY_AUX)
        is Ingredient.Tag -> writeVarUInt(DESCRIPTOR).writeString("item_tag").writeString(ingredient.tag).writeVarInt(ANY_AUX)
    }
    return writeVarInt(ingredient.count)
}

/** Whether an ingredient is there, as the descriptor's variant: none, or one whose kind follows by name. */
private const val EMPTY_DESCRIPTOR = 0
private const val DESCRIPTOR = 1
