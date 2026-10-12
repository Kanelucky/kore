package org.koremc.inventory

import org.koremc.protocol.ContainerChange
import org.koremc.protocol.ContainerName
import org.koremc.protocol.SlotChange
import org.koremc.protocol.SlotRef
import org.koremc.protocol.StackAction
import org.koremc.protocol.StackRequest
import org.koremc.protocol.StackResponse
import org.koremc.registry.CRAFTING_TABLE
import org.koremc.registry.CreativeInventory
import org.koremc.registry.Ingredient
import org.koremc.registry.ItemStack
import org.koremc.registry.Recipe
import org.koremc.registry.Recipes
import org.koremc.registry.ShapedRecipe
import org.koremc.registry.ShapelessRecipe
import org.koremc.registry.sameKindAs
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(StackRequestHandler::class.java)

/** How many recent requests' results to remember, for later requests that name stacks by the request that made them. */
private const val REMEMBERED_REQUESTS = 64

/** Something a request asks that the server won't do, which rejects the whole request. */
private class Rejection(message: String) : Exception(message)

/**
 * Carries out a player's item stack requests on their [inventory]. Each
 * request is a transaction the client has already predicted the result of.
 * The server either agrees, reporting every slot it changed and the ID of
 * each stack now there, or rejects it and changes nothing, which makes the
 * client undo its prediction.
 *
 * The client doesn't wait for one answer before making its next request,
 * so a request can name a stack an earlier one made by that request's ID
 * (requests are numbered below zero) rather than the stack's own.
 *
 * Crafting takes a request of its own: the recipe, the ingredients it uses
 * up, then moving what it made out of the created output. A craft from the
 * crafting grid must have its recipe laid out there, and Kore uses up what
 * the recipe takes from each slot. A craft from the recipe book takes its
 * ingredients from wherever the client says, which must be what the recipe
 * takes.
 */
class StackRequestHandler(
    private val inventory: PlayerInventory,
    private val creative: CreativeInventory,
    /** Whether the player may take items from the creative inventory, and destroy them. */
    private val creativeMode: () -> Boolean,
    /** Throws items the player dropped into the world, once the request dropping them is accepted. */
    private val onDrop: (ItemStack) -> Unit,
    private val recipes: Recipes = Recipes.NONE,
) {
    /** What each recent request left in the slots it changed, by request ID. */
    private val recent = object : LinkedHashMap<Int, Map<Location, Int>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Map<Location, Int>>) = size > REMEMBERED_REQUESTS
    }

    /**
     * Remembers that, until it hears what they hold now, the client names
     * the stacks in [slots] of the player's items by [requestId]: the ID of a
     * transaction from before item stack requests, which changed them.
     */
    fun rememberLegacy(requestId: Int, slots: Collection<Int>) {
        recent[requestId] = slots.mapNotNull { slot -> inventory.items[slot]?.let { Location(inventory.items, slot) to it.id } }.toMap()
    }

    fun handle(request: StackRequest): StackResponse {
        val transaction = Transaction(request.id)
        return try {
            request.actions.forEach(transaction::apply)
            transaction.settleCraft()
            recent[request.id] = transaction.stackIds()
            transaction.dropped.forEach(onDrop)
            StackResponse(request.id, accepted = true, transaction.changes())
        } catch (e: Rejection) {
            transaction.undo()
            log.debug("Rejected item stack request {}: {}", request.id, e.message)
            StackResponse(request.id, accepted = false)
        }
    }

    private inner class Transaction(private val requestId: Int) {
        /** What each changed slot held before, to put back if the request fails. */
        private val before = LinkedHashMap<Location, SlotStack?>()

        /** Each changed slot, by the container name and slot the client called it. */
        private val named = LinkedHashMap<Pair<Int, Int>, Location>()

        /** What the player dropped, to throw into the world if the request goes through. */
        val dropped = mutableListOf<ItemStack>()

        /** The craft the request makes, once it names one. */
        private var craft: Craft? = null

        fun apply(action: StackAction) {
            when (action) {
                is StackAction.Transfer -> transfer(action)
                is StackAction.Swap -> swap(action)
                is StackAction.Destroy -> destroy(action)
                is StackAction.CraftCreative -> craftCreative(action)
                is StackAction.Craft -> startCraft(action)
                is StackAction.Consume -> consume(action)
                is StackAction.Create -> create(action)
                is StackAction.Drop -> drop(action)
                is StackAction.Informational -> {}
                is StackAction.Unsupported -> throw Rejection("action ${action.type} isn't supported yet")
            }
        }

        private fun transfer(action: StackAction.Transfer) {
            val (count, sourceRef, destinationRef) = action
            if (destinationRef.container == ContainerName.CREATED_OUTPUT) throw Rejection("nothing can be put in the created output")
            // What a craft makes is there to take once it's certain the craft can be made.
            if (sourceRef.container == ContainerName.CREATED_OUTPUT) settleCraft()
            val source = verified(sourceRef)
            val destination = verified(destinationRef)
            if (source == destination) throw Rejection("$sourceRef is both source and destination")

            val moving = source.content() ?: throw Rejection("$sourceRef is empty")
            if (count !in 1..moving.stack.count) throw Rejection("can't move $count of ${moving.stack.count}")
            val existing = destination.content()
            if (existing != null && !existing.stack.sameKindAs(moving.stack)) throw Rejection("$destinationRef holds something else")
            val total = (existing?.stack?.count ?: 0) + count
            if (total > moving.stack.item.maxStackSize) throw Rejection("$total of ${moving.stack.item.name} won't fit in one slot")

            set(sourceRef, source, moving.withCount(moving.stack.count - count))
            // A whole stack keeps its ID as it moves. Part of one starts a new stack, unless it joins one already there.
            val arriving = existing?.withCount(total) ?: SlotStack(moving.stack.copy(count = count), if (count == moving.stack.count) moving.id else newStackId())
            set(destinationRef, destination, arriving)
        }

        private fun swap(action: StackAction.Swap) {
            val source = verified(action.source)
            val destination = verified(action.destination)
            val sourceStack = source.content()
            set(action.source, source, destination.content())
            set(action.destination, destination, sourceStack)
        }

        private fun drop(action: StackAction.Drop) {
            val source = verified(action.source)
            val stack = source.content() ?: throw Rejection("${action.source} is empty")
            if (action.count !in 1..stack.stack.count) throw Rejection("can't drop ${action.count} of ${stack.stack.count}")
            set(action.source, source, stack.withCount(stack.stack.count - action.count))
            dropped += stack.stack.copy(count = action.count)
        }

        private fun destroy(action: StackAction.Destroy) {
            if (!creativeMode()) throw Rejection("only creative players can destroy items")
            val source = verified(action.source)
            val stack = source.content() ?: throw Rejection("${action.source} is empty")
            if (action.count !in 1..stack.stack.count) throw Rejection("can't destroy ${action.count} of ${stack.stack.count}")
            set(action.source, source, stack.withCount(stack.stack.count - action.count))
        }

        /** Puts a full stack of the picked creative item in the created output, for the request's next action to move. */
        private fun craftCreative(action: StackAction.CraftCreative) {
            if (!creativeMode()) throw Rejection("only creative players can take creative items")
            val picked = creative.items.getOrNull(action.creativeId - 1) ?: throw Rejection("there's no creative item ${action.creativeId}")
            val output = inventory.locate(ContainerName.CREATED_OUTPUT, CREATED_OUTPUT_SLOT)!!
            val stack = picked.stack.copy(count = picked.stack.item.maxStackSize)
            set(SlotRef(ContainerName.CREATED_OUTPUT, CREATED_OUTPUT_SLOT, stackId = 0), output, SlotStack(stack, newStackId()))
        }

        /**
         * Starts a craft of the recipe [action] names, which must be one the
         * crafting grid makes. From the grid, the recipe must be laid out
         * there, and what it takes is used up at once. From the recipe book,
         * the consumptions that follow say where the ingredients come from.
         */
        private fun startCraft(action: StackAction.Craft) {
            if (craft != null) throw Rejection("one request crafts once")
            val recipe = recipes[action.recipe] ?: throw Rejection("there's no recipe ${action.recipe}")
            val (inputs, outputs, block) = when (recipe) {
                is ShapedRecipe -> Triple(recipe.inputs.filterNotNull(), recipe.outputs, recipe.block)
                is ShapelessRecipe -> Triple(recipe.inputs, recipe.outputs, recipe.block)
                else -> throw Rejection("recipe ${action.recipe} isn't made in a crafting grid")
            }
            if (block != CRAFTING_TABLE) throw Rejection("recipe ${action.recipe} is made in a $block")
            if (action.times !in 1..MAX_CRAFTS) throw Rejection("can't craft ${action.times} times")
            val started = Craft(recipe, inputs, outputs, action.times, action.auto)
            craft = started
            if (action.auto) return

            val grid = inventory.craftingGrid.map { slot -> inventory.ui[slot]?.stack }
            val uses = recipes.gridUses(recipe, grid, action.times) ?: throw Rejection("the crafting grid doesn't hold recipe ${action.recipe}")
            for ((index, count) in uses) {
                val slot = inventory.craftingGrid.first + index
                val there = inventory.ui[slot]!!
                started.consumed += there.stack.copy(count = count)
                set(SlotRef(ContainerName.CRAFTING_INPUT, slot, stackId = 0), Location(inventory.ui, slot), there.withCount(there.stack.count - count))
            }
        }

        /** Uses up what [action] says for a craft from the recipe book. From the grid, Kore has used up what the recipe takes already. */
        private fun consume(action: StackAction.Consume) {
            val pending = craft ?: throw Rejection("nothing's being crafted to use up ${action.source} for")
            if (!pending.auto) return
            if (pending.settled) throw Rejection("the craft's ingredients were all used up already")
            val source = verified(action.source)
            val stack = source.content() ?: throw Rejection("${action.source} is empty")
            if (action.count !in 1..stack.stack.count) throw Rejection("can't use up ${action.count} of ${stack.stack.count}")
            pending.consumed += stack.stack.copy(count = action.count)
            set(action.source, source, stack.withCount(stack.stack.count - action.count))
        }

        /** Puts the craft's result numbered [action] in the created output, for a recipe that makes more than one thing. */
        private fun create(action: StackAction.Create) {
            settleCraft()
            val pending = craft ?: throw Rejection("nothing's been crafted to create")
            val result = pending.results.getOrNull(action.result) ?: throw Rejection("the craft has no result ${action.result}")
            pending.results[action.result] = null
            createdOutput(result)
        }

        /**
         * Makes sure the craft can be made, once its ingredients are used up:
         * from the recipe book, they must be what the recipe takes. Then a
         * recipe that makes one thing puts it in the created output.
         */
        fun settleCraft() {
            val pending = craft ?: return
            if (pending.settled) return
            pending.settled = true
            if (pending.auto && !recipes.takes(pending.inputs, pending.consumed, pending.times)) throw Rejection("what the recipe book used up isn't what the recipe takes")
            // A recipe that keeps data, like dyeing a shulker box, passes on what the item it changes held.
            val kept = if ((pending.recipe as? ShapelessRecipe)?.keepsData == true) pending.consumed.firstOrNull { it.nbt.isNotEmpty() }?.nbt else null
            pending.results += pending.outputs.map { output -> output.copy(count = output.count * pending.times, nbt = kept ?: output.nbt) }
            if (pending.results.size == 1) createdOutput(pending.results.removeAt(0)!!)
        }

        private fun createdOutput(stack: ItemStack) {
            val output = inventory.locate(ContainerName.CREATED_OUTPUT, CREATED_OUTPUT_SLOT)!!
            set(SlotRef(ContainerName.CREATED_OUTPUT, CREATED_OUTPUT_SLOT, stackId = 0), output, SlotStack(stack, newStackId()))
        }

        /** The slot [ref] names, once it's certain to hold the stack the client expects there. */
        private fun verified(ref: SlotRef): Location {
            val location = inventory.locate(ref.container, ref.slot) ?: throw Rejection("Kore doesn't keep $ref")
            val expected = when {
                ref.stackId >= 0 -> ref.stackId
                ref.stackId == requestId -> stackIds()[location] ?: throw Rejection("$ref names a stack this request hasn't made")
                else -> recent[ref.stackId]?.get(location) ?: throw Rejection("$ref names a stack Kore doesn't remember")
            }
            val actual = location.content()?.id ?: 0
            if (expected != actual) throw Rejection("$ref expects stack $expected, but stack $actual is there")
            return location
        }

        private fun set(ref: SlotRef, location: Location, stack: SlotStack?) {
            if (location !in before) before[location] = location.content()
            location.container[location.slot] = stack
            named[ref.container to ref.slot] = location
        }

        fun stackIds(): Map<Location, Int> = before.keys.associateWith { it.content()?.id ?: 0 }

        fun changes(): List<ContainerChange> = named.entries.groupBy({ it.key.first }) { (key, location) ->
            val stack = location.content()
            SlotChange(key.second, stack?.stack?.count ?: 0, stack?.id ?: 0)
        }.map { (container, slots) -> ContainerChange(container, slots) }

        fun undo() = before.forEach { (location, stack) -> location.container[location.slot] = stack }
    }

    private fun newStackId() = inventory.newStackId()
}

/** The most times one request can craft a recipe. */
private const val MAX_CRAFTS = 64

/** A craft a request makes: what the recipe takes and makes, how many times, and what the request has used up for it. */
private class Craft(val recipe: Recipe, val inputs: List<Ingredient>, val outputs: List<ItemStack>, val times: Int, val auto: Boolean) {
    val consumed = mutableListOf<ItemStack>()
    val results = mutableListOf<ItemStack?>()
    var settled = false
}

/**
 * Where the crafting [grid], row by row, holds [recipe] laid out [times]
 * over: how many of each slot's items it uses up, by the slot's place in
 * the grid. Null if it doesn't. A shaped recipe's pattern can go anywhere
 * in the grid, mirrored too if it assumes symmetry, with nothing outside
 * it. A shapeless recipe takes each item in the grid once.
 */
internal fun Recipes.gridUses(recipe: Recipe, grid: List<ItemStack?>, times: Int): Map<Int, Int>? {
    val size = if (grid.size == LARGE_GRID.count()) 3 else 2
    return when (recipe) {
        is ShapedRecipe -> shapedUses(recipe, grid, size, times)
        is ShapelessRecipe -> shapelessUses(recipe.inputs, grid.withIndex().filter { it.value != null }.map { it.index to it.value!! }, times)
        else -> null
    }
}

private fun Recipes.shapedUses(recipe: ShapedRecipe, grid: List<ItemStack?>, size: Int, times: Int): Map<Int, Int>? {
    if (recipe.width > size || recipe.height > size) return null
    val mirrors = if (recipe.assumeSymmetry) listOf(false, true) else listOf(false)
    for (mirror in mirrors) for (top in 0..size - recipe.height) for (left in 0..size - recipe.width) {
        val uses = HashMap<Int, Int>()
        val fits = (0 until size * size).all { index ->
            val column = index % size - left
            val row = index / size - top
            val inside = column in 0 until recipe.width && row in 0 until recipe.height
            val ingredient = if (inside) recipe.inputs[row * recipe.width + if (mirror) recipe.width - 1 - column else column] else null
            val stack = grid[index]
            when {
                ingredient == null -> stack == null
                stack == null || !accepts(ingredient, stack) || stack.count < ingredient.count * times -> false
                else -> true.also { uses[index] = ingredient.count * times }
            }
        }
        if (fits) return uses
    }
    return null
}

/** Which of [stacks], each by its place in the grid, go to which of [inputs], each once, or null if they can't all. */
private fun Recipes.shapelessUses(inputs: List<Ingredient>, stacks: List<Pair<Int, ItemStack>>, times: Int): Map<Int, Int>? {
    if (inputs.size != stacks.size) return null
    val uses = HashMap<Int, Int>()
    fun assign(next: Int): Boolean {
        if (next == inputs.size) return true
        val ingredient = inputs[next]
        for ((index, stack) in stacks) {
            if (index in uses || !accepts(ingredient, stack) || stack.count < ingredient.count * times) continue
            uses[index] = ingredient.count * times
            if (assign(next + 1)) return true
            uses.remove(index)
        }
        return false
    }
    return if (assign(0)) uses else null
}

/**
 * Whether [consumed] is what [inputs] take, [times] over: as many items as
 * they take altogether, none they don't take, and enough for each.
 */
private fun Recipes.takes(inputs: List<Ingredient>, consumed: List<ItemStack>, times: Int): Boolean {
    if (consumed.sumOf { it.count } != inputs.sumOf { it.count } * times) return false
    if (consumed.any { stack -> inputs.none { accepts(it, stack) } }) return false
    return inputs.all { ingredient ->
        val needed = inputs.filter { it == ingredient }.sumOf { it.count } * times
        consumed.filter { accepts(ingredient, it) }.sumOf { it.count } >= needed
    }
}

private fun Location.content(): SlotStack? = container[slot]

/** This stack with [count] items, or nothing if that's none. */
private fun SlotStack.withCount(count: Int): SlotStack? = if (count == 0) null else copy(stack = stack.copy(count = count))
