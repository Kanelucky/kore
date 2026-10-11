package org.koremc.protocol

import io.netty.buffer.ByteBuf

internal const val ITEM_STACK_REQUEST = 147
private const val ITEM_STACK_RESPONSE = 148

/** The containers Kore keeps, by the numbers the client names them with in item stack requests. */
object ContainerName {
    const val ARMOR = 6
    const val HOTBAR_AND_INVENTORY = 12
    const val CRAFTING_INPUT = 13
    const val HOTBAR = 28
    const val INVENTORY = 29
    const val OFFHAND = 34
    const val CURSOR = 59
    /** Where crafted and creative items appear, before the client moves them somewhere. */
    const val CREATED_OUTPUT = 60
}

/** A slot a request names: its container, the slot there, and the ID of the stack the client expects in it. */
data class SlotRef(val container: Int, val slot: Int, val stackId: Int)

/**
 * One step of an item stack request. Actions are numbered by Mojang's
 * current scheme; each also carries its number from before 1.26.40 removed
 * two, which Kore reads past.
 */
sealed interface StackAction {
    /** Moves [count] items from [source] to [destination]. The client's Take and Place are the same move to the server. */
    data class Transfer(val count: Int, val source: SlotRef, val destination: SlotRef) : StackAction

    data class Swap(val source: SlotRef, val destination: SlotRef) : StackAction

    data class Drop(val count: Int, val source: SlotRef) : StackAction

    /** Removes items for good, as the creative inventory's bin does. */
    data class Destroy(val count: Int, val source: SlotRef) : StackAction

    /** Puts a stack of the creative item numbered [creativeId] in the created output slot. */
    data class CraftCreative(val creativeId: Int, val times: Int) : StackAction

    /** An action that only describes what others do, like consuming crafting inputs. It changes nothing itself. */
    data class Informational(val type: Int) : StackAction

    /** An action Kore can't carry out yet, like crafting a recipe, which fails its request. */
    data class Unsupported(val type: Int) : StackAction
}

/** One request: actions the client has already predicted, which the server accepts or rejects as a whole. */
data class StackRequest(val id: Int, val actions: List<StackAction>)

/** The client's item stack requests, sent whenever the player moves items around their inventory. */
data class ItemStackRequest(val requests: List<StackRequest>) : ClientPacket

/** A changed slot, as a response reports it: how many items are now there, and the stack's ID (0 for none). */
data class SlotChange(val slot: Int, val count: Int, val stackId: Int)

/** The changed slots in one container, under the name the client used for it. */
data class ContainerChange(val container: Int, val slots: List<SlotChange>)

/** The answer to one request: accepted, with every slot it changed, or rejected, which makes the client undo it. */
data class StackResponse(val requestId: Int, val accepted: Boolean, val containers: List<ContainerChange> = emptyList())

data class ItemStackResponse(val responses: List<StackResponse>) : ServerPacket {
    override val id get() = ITEM_STACK_RESPONSE

    override fun encodeBody(out: ByteBuf) {
        out.writeVarUInt(responses.size)
        for (response in responses) {
            out.writeByte(if (response.accepted) 0 else 1)
            out.writeVarInt(response.requestId)
            out.writeBoolean(response.containers.isNotEmpty())
            if (response.containers.isEmpty()) continue
            out.writeVarUInt(response.containers.size)
            for (container in response.containers) {
                out.writeByte(container.container)
                out.writeBoolean(false) // Dynamic container ID, for bundles
                out.writeVarUInt(container.slots.size)
                for (slot in container.slots) {
                    out.writeByte(slot.slot)
                    out.writeByte(slot.slot) // The same slot again, as the hotbar slot
                    out.writeByte(slot.count)
                    out.writeBoolean(slot.stackId > 0)
                    if (slot.stackId > 0) out.writeVarInt(slot.stackId)
                    out.writeString("") // Custom name
                    out.writeBoolean(false) // Filtered custom name
                    out.writeVarInt(0) // Durability correction
                }
            }
        }
    }
}

/**
 * Reads the requests in [buffer]. One action carries a structure Kore
 * doesn't read: auto-crafting's list of ingredients, which can't come while
 * Kore sends no recipes. A request with one ends with an
 * [StackAction.Unsupported], and reading stops there, as nothing after it
 * can be found.
 */
internal fun decodeItemStackRequest(buffer: ByteBuf): ItemStackRequest {
    val requests = ArrayList<StackRequest>()
    repeat(buffer.readVarUInt().also { if (it > 100) throw PacketException("$it item stack requests in one packet") }) {
        val id = buffer.readVarInt()
        val actions = ArrayList<StackAction>()
        val count = buffer.readVarUInt()
        if (count > 100) throw PacketException("$count actions in one item stack request")
        for (i in 0 until count) {
            val action = buffer.readStackAction()
            actions += action
            if (action is StackAction.Unsupported && action.type in UNREADABLE_ACTIONS) {
                requests += StackRequest(id, actions)
                return ItemStackRequest(requests)
            }
        }
        buffer.readList { readString() } // Strings to filter, like an anvil's new name
        buffer.readIntLE() // Why they need filtering
        requests += StackRequest(id, actions)
    }
    return ItemStackRequest(requests)
}

// Action types, as Mojang numbers them now.
private const val TAKE = 0
private const val PLACE = 1
private const val SWAP = 2
private const val DROP = 3
private const val DESTROY = 4
private const val CONSUME = 5
private const val CREATE = 6
private const val LAB_TABLE_COMBINE = 7
private const val BEACON_PAYMENT = 8
private const val MINE_BLOCK = 9
private const val CRAFT_RECIPE = 10
private const val CRAFT_RECIPE_AUTO = 11
private const val CRAFT_CREATIVE = 12
private const val CRAFT_RECIPE_OPTIONAL = 13
private const val CRAFT_REPAIR_AND_DISENCHANT = 14
private const val CRAFT_LOOM = 15
private const val CRAFT_NON_IMPLEMENTED = 16
private const val CRAFT_RESULTS = 17

private val UNREADABLE_ACTIONS = setOf(CRAFT_RECIPE_AUTO)

private fun ByteBuf.readStackAction(): StackAction {
    val type = readVarUInt()
    readUnsignedByte() // The type again, as it was numbered before 1.26.40
    return when (type) {
        TAKE, PLACE -> StackAction.Transfer(readUnsignedByte().toInt(), readSlotRef(), readSlotRef())
        SWAP -> StackAction.Swap(readSlotRef(), readSlotRef())
        DROP -> StackAction.Drop(readUnsignedByte().toInt(), readSlotRef()).also { readBoolean() } // Whether to throw it somewhere random
        DESTROY -> StackAction.Destroy(readUnsignedByte().toInt(), readSlotRef())
        CONSUME -> StackAction.Informational(type).also { readUnsignedByte(); readSlotRef() }
        CREATE -> StackAction.Unsupported(type).also { readUnsignedByte() } // Results slot
        LAB_TABLE_COMBINE, CRAFT_NON_IMPLEMENTED -> StackAction.Unsupported(type)
        BEACON_PAYMENT -> StackAction.Unsupported(type).also { readVarInt(); readVarInt() }
        MINE_BLOCK -> StackAction.Unsupported(type).also { readVarInt(); readVarInt(); readIntLE() }
        CRAFT_RECIPE -> StackAction.Unsupported(type).also { readVarUInt(); readUnsignedByte() }
        CRAFT_RECIPE_AUTO -> StackAction.Unsupported(type)
        CRAFT_CREATIVE -> StackAction.CraftCreative(readVarUInt(), readUnsignedByte().toInt())
        CRAFT_RECIPE_OPTIONAL -> StackAction.Unsupported(type).also { readVarUInt(); readIntLE() }
        CRAFT_REPAIR_AND_DISENCHANT -> StackAction.Unsupported(type).also { readIntLE(); readUnsignedByte(); readVarInt() }
        CRAFT_LOOM -> StackAction.Unsupported(type).also { readString(); readUnsignedByte() }
        CRAFT_RESULTS -> StackAction.Informational(type).also { readList { skipRequestItem() }; readUnsignedByte() }
        else -> throw PacketException("Unknown item stack request action $type")
    }
}

/** A slot reference: the container's name and dynamic ID, the slot, and the stack ID as a plain little-endian int. */
private fun ByteBuf.readSlotRef(): SlotRef {
    val container = readUnsignedByte().toInt()
    if (readBoolean()) readIntLE() // Dynamic container ID
    return SlotRef(container, readUnsignedByte().toInt(), readIntLE())
}

/** Skips an item as the deprecated crafting results describe it: by name, then count, block and a buffer of extras. */
private fun ByteBuf.skipRequestItem() {
    val variant = readVarUInt()
    readUnsignedByte() // The variant again
    if (variant != 0) {
        readString() // Name
        readVarInt() // Aux value
    }
    readShortLE() // Count
    readVarUInt() // Block network ID
    readByteArray() // Extras
}
