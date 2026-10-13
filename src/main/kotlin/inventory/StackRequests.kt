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
import org.koremc.registry.Enchantments
import org.koremc.registry.Ingredient
import org.koremc.registry.ItemStack
import org.koremc.registry.Recipe
import org.koremc.registry.Recipes
import org.koremc.registry.ShapedRecipe
import org.koremc.registry.ShapelessRecipe
import org.koremc.registry.SmithingTransformRecipe
import org.koremc.registry.SmithingTrimRecipe
import org.koremc.registry.customName
import org.koremc.registry.damage
import org.koremc.registry.sameKindAs
import org.koremc.registry.withEnchantments
import org.koremc.world.Cartographed
import org.koremc.world.MapData
import org.koremc.world.paysBeacon
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(StackRequestHandler::class.java)

/** How many recent requests' results to remember, for later requests that name stacks by the request that made them. */
private const val REMEMBERED_REQUESTS = 64

/** Something a request asks that the server won't do, which rejects the whole request. */
private class Rejection(message: String) : Exception(message)

/**
 * What crafting at workstations takes besides items: the player's
 * experience level, which anvils and enchanting cost, what the enchanting
 * table they have open offers for an item, the powers the beacon they
 * have open can give, and what the cartography table they have open makes.
 */
interface Crafter {
    val experienceLevel: Int

    fun enchantingOffers(stack: ItemStack): List<EnchantingOffer>

    /** Whether the beacon the player has open can give [primary], and [secondary] with it, or 0 for none, on the pyramid it stands on. */
    fun beaconGives(primary: Int, secondary: Int): Boolean = false

    /** What the cartography table the player has open makes of what's in its slots, [input] and [additional], named [name], if anything. */
    fun cartograph(input: ItemStack?, additional: ItemStack?, name: String?): Cartographed? = null

    companion object {
        /** A player with no levels, and no enchanting table. */
        val NONE = object : Crafter {
            override val experienceLevel = 0

            override fun enchantingOffers(stack: ItemStack) = emptyList<EnchantingOffer>()
        }
    }
}

/**
 * A craft at a workstation that a request made, once it's accepted:
 * where, the levels it cost the player, the experience it gave back, the
 * powers a beacon was paid for, and what a cartography table made, with
 * the maps it started, if any. The client works out a cartography table's
 * maps its own way, so it hears what Kore made of them.
 */
data class Crafted(
    val workstation: Workstation,
    val levels: Int = 0,
    val experience: Int = 0,
    val beaconPowers: Pair<Int, Int>? = null,
    val made: ItemStack? = null,
    val newMaps: List<MapData> = emptyList(),
)

private const val STONECUTTER = "stonecutter"
private const val CARTOGRAPHY_TABLE = "cartography_table"
private const val LAPIS_LAZULI = "minecraft:lapis_lazuli"

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
 *
 * Workstations craft from slots of their own, which Kore uses up as the
 * craft needs: a stonecutter's recipe, a smithing table's upgrade or trim,
 * an anvil's mending, enchanting and naming, a grindstone's disenchanting,
 * a loom's pattern, an enchanting table's offer, and a cartography table's
 * recipes and maps. What they make goes in the created output too. Paying
 * a beacon uses up what's in its payment slot.
 */
class StackRequestHandler(
    private val inventory: PlayerInventory,
    private val creative: CreativeInventory,
    /** Whether the player may take items from the creative inventory, and destroy them. */
    private val creativeMode: () -> Boolean,
    /** Throws items the player dropped into the world, once the request dropping them is accepted. */
    private val onDrop: (ItemStack) -> Unit,
    private val recipes: Recipes = Recipes.NONE,
    private val enchantments: Enchantments = Enchantments.NONE,
    private val crafter: Crafter = Crafter.NONE,
    /** Hears of each craft at a workstation, once the request making it is accepted. */
    private val onCrafted: (Crafted) -> Unit = {},
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
        val transaction = Transaction(request.id, request.filterStrings, paysBeacon = request.actions.any { it is StackAction.BeaconPayment })
        return try {
            request.actions.forEach(transaction::apply)
            transaction.settleCraft()
            transaction.settlePayment()
            recent[request.id] = transaction.stackIds()
            transaction.dropped.forEach(onDrop)
            transaction.crafted?.let(onCrafted)
            StackResponse(request.id, accepted = true, transaction.changes())
        } catch (e: Rejection) {
            transaction.undo()
            log.debug("Rejected item stack request {}: {}", request.id, e.message)
            StackResponse(request.id, accepted = false)
        }
    }

    private inner class Transaction(
        private val requestId: Int,
        private val filterStrings: List<String>,
        /** Whether the request pays a beacon, which uses up what's in its payment slot, outside creative too. */
        private val paysBeacon: Boolean,
    ) {
        /** What each changed slot held before, to put back if the request fails. */
        private val before = LinkedHashMap<Location, SlotStack?>()

        /** Each changed slot, by the container name and slot the client called it. */
        private val named = LinkedHashMap<Pair<Int, Int>, Location>()

        /** What the player dropped, to throw into the world if the request goes through. */
        val dropped = mutableListOf<ItemStack>()

        /** The craft the request makes, once it names one. */
        private var craft: Craft? = null

        /** The craft at a workstation the request made, if any. */
        var crafted: Crafted? = null
            private set

        /** What the request took from a beacon's payment slot, which the client does by destroying it or using it up. */
        private var payment: ItemStack? = null

        /** Whether the request paid a beacon without taking anything from its payment slot yet. */
        private var paymentDue = false

        fun apply(action: StackAction) {
            when (action) {
                is StackAction.Transfer -> transfer(action)
                is StackAction.Swap -> swap(action)
                is StackAction.Destroy -> destroy(action)
                is StackAction.CraftCreative -> craftCreative(action)
                is StackAction.Craft -> startCraft(action)
                is StackAction.Consume -> consume(action)
                is StackAction.Create -> create(action)
                is StackAction.CraftOptional -> if (inventory.workstation == Workstation.CARTOGRAPHY_TABLE) cartography(action) else anvil(action)
                is StackAction.CraftLoom -> loom(action)
                is StackAction.CraftGrindstone -> grindstone()
                is StackAction.BeaconPayment -> payBeacon(action)
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
            if (total > inventory.limit(destination, moving.stack)) throw Rejection("$total of ${moving.stack.item.name} won't fit in $destinationRef")

            set(sourceRef, source, moving.withCount(moving.stack.count - count))
            // A whole stack keeps its ID as it moves. Part of one starts a new stack, unless it joins one already there.
            val arriving = existing?.withCount(total) ?: SlotStack(moving.stack.copy(count = count), if (count == moving.stack.count) moving.id else newStackId())
            set(destinationRef, destination, arriving)
        }

        private fun swap(action: StackAction.Swap) {
            val source = verified(action.source)
            val destination = verified(action.destination)
            val sourceStack = source.content()
            val destinationStack = destination.content()
            if (sourceStack != null && sourceStack.stack.count > inventory.limit(destination, sourceStack.stack)) throw Rejection("${action.destination} won't take what's in ${action.source}")
            if (destinationStack != null && destinationStack.stack.count > inventory.limit(source, destinationStack.stack)) throw Rejection("${action.source} won't take what's in ${action.destination}")
            set(action.source, source, destinationStack)
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
            if (paysBeacon && action.source.container == ContainerName.BEACON_PAYMENT) return takePayment(action.source, action.count)
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
            when (inventory.workstation) {
                Workstation.STONECUTTER -> return stonecut(action)
                Workstation.SMITHING_TABLE -> return smith(action)
                Workstation.ENCHANTING_TABLE -> return enchant(action)
                Workstation.CARTOGRAPHY_TABLE -> return cartographyRecipe(action)
                null, Workstation.CRAFTING_TABLE -> {}
                else -> throw Rejection("${inventory.workstation} crafts no recipes")
            }
            val recipe = recipes[action.recipe] ?: throw Rejection("there's no recipe ${action.recipe}")
            val (inputs, outputs, block) = when (recipe) {
                is ShapedRecipe -> Triple(recipe.inputs.filterNotNull(), recipe.outputs, recipe.block)
                is ShapelessRecipe -> Triple(recipe.inputs, recipe.outputs, recipe.block)
                else -> throw Rejection("recipe ${action.recipe} isn't made in a crafting grid")
            }
            if (block != CRAFTING_TABLE) throw Rejection("recipe ${action.recipe} is made in a $block")
            checkTimes(action.times)
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

        /** Cuts what's in the stonecutter into what the recipe [action] names makes, as many times over as it says. */
        private fun stonecut(action: StackAction.Craft) {
            val recipe = recipes[action.recipe] as? ShapelessRecipe ?: throw Rejection("there's no recipe ${action.recipe}")
            if (recipe.block != STONECUTTER || recipe.inputs.size != 1) throw Rejection("recipe ${action.recipe} isn't a stonecutter's")
            checkTimes(action.times)
            val input = inSlot(ContainerName.STONECUTTER_INPUT) ?: throw Rejection("the stonecutter is empty")
            if (!recipes.accepts(recipe.inputs[0], input)) throw Rejection("recipe ${action.recipe} doesn't cut ${input.item.name}")
            useUp(ContainerName.STONECUTTER_INPUT, recipe.inputs[0].count * action.times)
            val output = recipe.outputs[0]
            made(output.copy(count = output.count * action.times), Crafted(Workstation.STONECUTTER))
        }

        /** Upgrades or trims what's in the smithing table, as the recipe [action] names says, using up one of each of its three items. */
        private fun smith(action: StackAction.Craft) {
            val recipe = recipes[action.recipe]
            val base = inSlot(ContainerName.SMITHING_TABLE_INPUT) ?: throw Rejection("the smithing table has nothing to work on")
            val addition = inSlot(ContainerName.SMITHING_TABLE_MATERIAL) ?: throw Rejection("the smithing table has no material")
            val template = inSlot(ContainerName.SMITHING_TABLE_TEMPLATE) ?: throw Rejection("the smithing table has no template")
            val output = when (recipe) {
                is SmithingTransformRecipe -> recipe.output.copy(count = 1, nbt = base.nbt)
                    .takeIf { recipes.accepts(recipe.template, template) && recipes.accepts(recipe.base, base) && recipes.accepts(recipe.addition, addition) }
                is SmithingTrimRecipe -> trim(base, template, addition)
                    ?.takeIf { recipes.accepts(recipe.template, template) && recipes.accepts(recipe.base, base) && recipes.accepts(recipe.addition, addition) }
                else -> throw Rejection("recipe ${action.recipe} isn't a smithing table's")
            } ?: throw Rejection("recipe ${action.recipe} doesn't work ${base.item.name} with ${addition.item.name} and ${template.item.name}")
            for (name in listOf(ContainerName.SMITHING_TABLE_INPUT, ContainerName.SMITHING_TABLE_MATERIAL, ContainerName.SMITHING_TABLE_TEMPLATE)) useUp(name, 1)
            made(output, Crafted(Workstation.SMITHING_TABLE))
        }

        /**
         * Enchants what's in the enchanting table as the offer [action] names
         * says: outside creative, for as many levels and lapis lazuli as its
         * place among the offers, from a player with at least the levels it
         * needs. A book becomes an enchanted book.
         */
        private fun enchant(action: StackAction.Craft) {
            val index = action.recipe - recipes.nextNetworkId
            val input = inSlot(ContainerName.ENCHANTING_INPUT) ?: throw Rejection("the enchanting table is empty")
            val offer = crafter.enchantingOffers(input).getOrNull(index)?.takeIf { it.enchantments.isNotEmpty() }
                ?: throw Rejection("the enchanting table offers no ${action.recipe} for ${input.item.name}")
            val levels = index + 1
            val creative = creativeMode()
            if (!creative) {
                if (crafter.experienceLevel < maxOf(offer.cost, levels)) throw Rejection("enchanting needs level ${offer.cost}")
                val lapis = inSlot(ContainerName.ENCHANTING_MATERIAL)
                if (lapis?.item?.name != LAPIS_LAZULI || lapis.count < levels) throw Rejection("enchanting needs $levels lapis lazuli")
                useUp(ContainerName.ENCHANTING_MATERIAL, levels)
            }
            useUp(ContainerName.ENCHANTING_INPUT, 1)
            val item = if (isBook(input)) enchantments.enchantedBook ?: throw Rejection("there's no enchanted book") else input.item
            made(input.copy(item = item, count = 1).withEnchantments(offer.enchantments), Crafted(Workstation.ENCHANTING_TABLE, levels = if (creative) 0 else levels))
        }

        /**
         * Works what's in the anvil, naming it by the filter string [action]
         * points at, if there is one. Outside creative, it mustn't cost more
         * levels than the player has, or be too expensive.
         */
        private fun anvil(action: StackAction.CraftOptional) {
            if (inventory.workstation != Workstation.ANVIL) throw Rejection("there's no anvil open")
            if (craft != null) throw Rejection("one request crafts once")
            val input = inSlot(ContainerName.ANVIL_INPUT) ?: throw Rejection("the anvil is empty")
            val material = inSlot(ContainerName.ANVIL_MATERIAL)
            val result = enchantments.anvil(input, material, filterStrings.getOrNull(action.nameIndex))
                ?: throw Rejection("the anvil does nothing with ${input.item.name} and ${material?.item?.name}")
            val creative = creativeMode()
            if (!creative && result.cost >= ANVIL_TOO_EXPENSIVE) throw Rejection("${result.cost} levels is too expensive")
            if (!creative && crafter.experienceLevel < result.cost) throw Rejection("the anvil costs ${result.cost} levels")
            useUp(ContainerName.ANVIL_INPUT, input.count)
            if (result.materialUsed > 0) useUp(ContainerName.ANVIL_MATERIAL, result.materialUsed)
            made(result.output, Crafted(Workstation.ANVIL, levels = if (creative) 0 else result.cost))
        }

        /** Weaves the pattern [action] names into the banners in the loom, using up a banner and a dye for each. */
        private fun loom(action: StackAction.CraftLoom) {
            if (inventory.workstation != Workstation.LOOM) throw Rejection("there's no loom open")
            if (craft != null) throw Rejection("one request crafts once")
            checkTimes(action.times)
            val banner = inSlot(ContainerName.LOOM_INPUT) ?: throw Rejection("the loom has no banner")
            val dye = inSlot(ContainerName.LOOM_DYE) ?: throw Rejection("the loom has no dye")
            if (banner.count < action.times || dye.count < action.times) throw Rejection("the loom hasn't enough for ${action.times}")
            val output = weave(banner, dye, inSlot(ContainerName.LOOM_MATERIAL), action.pattern, action.times)
                ?: throw Rejection("the loom can't weave ${action.pattern}")
            useUp(ContainerName.LOOM_INPUT, action.times)
            useUp(ContainerName.LOOM_DYE, action.times)
            made(output, Crafted(Workstation.LOOM))
        }

        /** Takes the enchantments off what's in the grindstone, and mends two of a kind together, for experience. */
        private fun grindstone() {
            if (inventory.workstation != Workstation.GRINDSTONE) throw Rejection("there's no grindstone open")
            if (craft != null) throw Rejection("one request crafts once")
            val first = inSlot(ContainerName.GRINDSTONE_INPUT)
            val second = inSlot(ContainerName.GRINDSTONE_ADDITIONAL)
            val ground = enchantments.grind(first, second)
                ?: throw Rejection("the grindstone does nothing with ${first?.item?.name} and ${second?.item?.name}")
            first?.let { useUp(ContainerName.GRINDSTONE_INPUT, it.count) }
            second?.let { useUp(ContainerName.GRINDSTONE_ADDITIONAL, it.count) }
            made(ground.output, Crafted(Workstation.GRINDSTONE, experience = ground.experience))
        }

        /**
         * Makes what the cartography table's own recipe [action] names, like
         * an empty map from paper, as many times over as it says, using up
         * what it takes from the table's two slots.
         */
        private fun cartographyRecipe(action: StackAction.Craft) {
            val recipe = recipes[action.recipe] as? ShapelessRecipe ?: throw Rejection("there's no recipe ${action.recipe}")
            if (recipe.block != CARTOGRAPHY_TABLE) throw Rejection("recipe ${action.recipe} isn't a cartography table's")
            checkTimes(action.times)
            val slots = listOf(ContainerName.CARTOGRAPHY_INPUT, ContainerName.CARTOGRAPHY_ADDITIONAL).mapNotNull { name -> inSlot(name)?.let { name to it } }
            val uses = recipes.shapelessUses(recipe.inputs, slots, action.times) ?: throw Rejection("the cartography table doesn't hold recipe ${action.recipe}")
            for ((name, count) in uses) useUp(name, count)
            val output = recipe.outputs[0]
            made(output.copy(count = output.count * action.times), Crafted(Workstation.CARTOGRAPHY_TABLE))
        }

        /**
         * Works the map in the cartography table, naming what it makes by the
         * filter string [action] points at, if there is one: zooming it out,
         * copying it, locking it, making it a locator map, or just naming it.
         */
        private fun cartography(action: StackAction.CraftOptional) {
            if (craft != null) throw Rejection("one request crafts once")
            val input = inSlot(ContainerName.CARTOGRAPHY_INPUT)
            val additional = inSlot(ContainerName.CARTOGRAPHY_ADDITIONAL)
            val worked = crafter.cartograph(input, additional, filterStrings.getOrNull(action.nameIndex))
                ?: throw Rejection("the cartography table does nothing with ${input?.item?.name} and ${additional?.item?.name}")
            if (worked.inputUsed > 0) useUp(ContainerName.CARTOGRAPHY_INPUT, worked.inputUsed)
            if (worked.additionalUsed > 0) useUp(ContainerName.CARTOGRAPHY_ADDITIONAL, worked.additionalUsed)
            made(worked.output, Crafted(Workstation.CARTOGRAPHY_TABLE, made = worked.output, newMaps = worked.newMaps))
        }

        /**
         * Pays the beacon the player has open for the powers [action] names,
         * which it must give on the pyramid it stands on, with what's in its
         * payment slot. The client takes that from the slot itself, by
         * destroying it, or Kore does once the request's done.
         */
        private fun payBeacon(action: StackAction.BeaconPayment) {
            if (inventory.workstation != Workstation.BEACON) throw Rejection("there's no beacon open")
            if (crafted != null) throw Rejection("one request pays once")
            val paid = payment ?: inSlot(ContainerName.BEACON_PAYMENT)
            if (paid?.paysBeacon != true) throw Rejection("the beacon hasn't been paid")
            if (!crafter.beaconGives(action.primary, action.secondary)) throw Rejection("the beacon doesn't give ${action.primary} and ${action.secondary}")
            paymentDue = payment == null
            crafted = Crafted(Workstation.BEACON, beaconPowers = action.primary to action.secondary)
        }

        /** Takes [count] from the beacon's payment slot at [source] to pay it with, which must be one of something that does. */
        private fun takePayment(source: SlotRef, count: Int) {
            if (payment != null) throw Rejection("the beacon's been paid already")
            val location = verified(source)
            val there = location.content() ?: throw Rejection("$source is empty")
            if (count != 1 || !there.stack.paysBeacon) throw Rejection("can't pay a beacon with $count of ${there.stack.item.name}")
            payment = there.stack.copy(count = 1)
            paymentDue = false
            set(source, location, there.withCount(there.stack.count - 1))
        }

        /** Takes what pays the beacon from its slot, if the request paid it without the client doing so. */
        fun settlePayment() {
            if (!paymentDue) return
            paymentDue = false
            useUp(ContainerName.BEACON_PAYMENT, 1)
        }

        /** What's in the open workstation's slot that the client calls [name]. */
        private fun inSlot(name: Int): ItemStack? = inventory.ui[inventory.workstation!!.slots.getValue(name)]?.stack

        /** Uses up [count] of what's in the open workstation's slot that the client calls [name], which must hold that many. */
        private fun useUp(name: Int, count: Int) {
            val slot = inventory.workstation!!.slots.getValue(name)
            val there = inventory.ui[slot] ?: throw Rejection("there's nothing in slot $slot to use up")
            if (count !in 1..there.stack.count) throw Rejection("can't use up $count of ${there.stack.count}")
            set(SlotRef(name, slot, stackId = 0), Location(inventory.ui, slot), there.withCount(there.stack.count - count))
        }

        /** Makes [output] what the request crafted at a workstation, to take from the created output. */
        private fun made(output: ItemStack, made: Crafted) {
            craft = Craft(null, emptyList(), listOf(output), times = 1, auto = false)
            crafted = made
        }

        private fun checkTimes(times: Int) {
            if (times !in 1..MAX_CRAFTS) throw Rejection("can't craft $times times")
        }

        /** Uses up what [action] says for a craft from the recipe book. From the grid, Kore has used up what the recipe takes already. */
        private fun consume(action: StackAction.Consume) {
            if (paysBeacon && action.source.container == ContainerName.BEACON_PAYMENT) return takePayment(action.source, action.count)
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
            SlotChange(key.second, stack?.stack?.count ?: 0, stack?.id ?: 0, stack?.stack?.customName.orEmpty(), stack?.stack?.damage ?: 0)
        }.map { (container, slots) -> ContainerChange(container, slots) }

        fun undo() = before.forEach { (location, stack) -> location.container[location.slot] = stack }
    }

    private fun newStackId() = inventory.newStackId()
}

/** The most times one request can craft a recipe. */
private const val MAX_CRAFTS = 64

/**
 * A craft a request makes: what the recipe takes and makes, how many
 * times, and what the request has used up for it. A workstation's has no
 * recipe, and has used up what it takes already.
 */
private class Craft(val recipe: Recipe?, val inputs: List<Ingredient>, val outputs: List<ItemStack>, val times: Int, val auto: Boolean) {
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
