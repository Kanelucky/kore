package org.koremc.inventory

import org.koremc.protocol.ContainerChange
import org.koremc.protocol.ContainerName
import org.koremc.protocol.SlotChange
import org.koremc.protocol.SlotRef
import org.koremc.protocol.StackAction
import org.koremc.protocol.StackRequest
import org.koremc.protocol.StackResponse
import org.koremc.registry.CreativeInventory
import org.koremc.registry.ItemStack
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
 */
class StackRequestHandler(
    private val inventory: PlayerInventory,
    private val creative: CreativeInventory,
    /** Whether the player may take items from the creative inventory, and destroy them. */
    private val creativeMode: () -> Boolean,
    /** Throws items the player dropped into the world, once the request dropping them is accepted. */
    private val onDrop: (ItemStack) -> Unit,
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

        fun apply(action: StackAction) {
            when (action) {
                is StackAction.Transfer -> transfer(action)
                is StackAction.Swap -> swap(action)
                is StackAction.Destroy -> destroy(action)
                is StackAction.CraftCreative -> craftCreative(action)
                is StackAction.Drop -> drop(action)
                is StackAction.Informational -> {}
                is StackAction.Unsupported -> throw Rejection("action ${action.type} isn't supported yet")
            }
        }

        private fun transfer(action: StackAction.Transfer) {
            val (count, sourceRef, destinationRef) = action
            if (destinationRef.container == ContainerName.CREATED_OUTPUT) throw Rejection("nothing can be put in the created output")
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

private fun Location.content(): SlotStack? = container[slot]

/** This stack with [count] items, or nothing if that's none. */
private fun SlotStack.withCount(count: Int): SlotStack? = if (count == 0) null else copy(stack = stack.copy(count = count))
