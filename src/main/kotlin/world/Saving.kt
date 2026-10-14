package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtMapBuilder
import org.cloudburstmc.nbt.NbtType
import org.koremc.inventory.Container
import org.koremc.inventory.SlotStack
import org.koremc.registry.AIR
import org.koremc.registry.BlockState
import org.koremc.registry.ItemStacks

/**
 * Where a world finds the chunks it doesn't hold yet, like those it saved
 * last time: each chunk's blocks, and what to put back with them once
 * they're in the world.
 */
fun interface ChunkLoader {
    fun load(pos: ChunkPos): LoadedChunk?

    companion object {
        /** A world saved nowhere, which makes every chunk afresh. */
        val NONE = ChunkLoader { null }
    }
}

/**
 * A chunk's blocks as they were saved, or null for a chunk whose blocks
 * weren't, which are the world's own, and what to put back in the world
 * with them once they're in, like the items lying there.
 */
class LoadedChunk(val chunk: Chunk?, val restore: FlatWorld.() -> Unit = {})

/** What the game saves of the items in a container and a machine's progress, which Kore keeps apart from a block's data. */
private val SAVED_APART = listOf("Items", "BurnTime", "CookTime", "BurnDuration", "StoredXPInt", "FuelAmount", "FuelTotal", "TransferCooldown")

/**
 * The game's name for the block entity of [state], one of the blocks Kore
 * keeps items, a machine or other data for apart from block entities, or
 * null for any other.
 */
private fun savedId(state: BlockState): String? = when {
    state.storage == Storage.SHULKER_BOX -> "ShulkerBox"
    state.storage == Storage.ENDER_CHEST -> "EnderChest"
    state.name == BEACON -> "Beacon"
    state.isComparator -> "Comparator"
    else -> blockEntityIdOf(state.name)
}

/**
 * Every block entity in the world as the game saves them, by chunk: what
 * each block keeps besides its state, with the items in chests, barrels,
 * shulker boxes and machines, how far along a furnace, brewing stand or
 * hopper is, the power a comparator gives, and a beacon's powers, even
 * with none chosen.
 */
internal fun FlatWorld.savedBlockEntities(): Map<ChunkPos, List<NbtMap>> {
    val positions = LinkedHashSet<BlockPos>()
    positions += blockEntityPositions
    positions += keptContainers.keys
    positions += keptMachines.keys
    positions += comparators
    positions += beacons.keys
    return positions.mapNotNull(::savedBlockEntity).groupBy { BlockPos(it.getInt("x"), it.getInt("y"), it.getInt("z")).chunk }
}

private fun FlatWorld.savedBlockEntity(pos: BlockPos): NbtMap? {
    val state = stateAt(pos) ?: return null
    val data = blockEntityAt(pos)?.toBuilder() ?: NbtMap.builder().putString("id", savedId(state) ?: return null)
    data.putInt("x", pos.x).putInt("y", pos.y).putInt("z", pos.z)
    if (!state.isLectern) keptContainers[pos]?.let(data::putItems)
    when (val machine = keptMachines[pos]) {
        is Furnace -> data.putItems(machine.slots)
            .putShort("BurnTime", machine.litTime.toShort())
            .putShort("BurnDuration", machine.litDuration.toShort())
            .putShort("CookTime", machine.cookTime.toShort())
            .putInt("StoredXPInt", machine.experience.toInt())
        is BrewingStand -> data.putItems(machine.slots)
            .putShort("CookTime", machine.brewTime.toShort())
            .putShort("FuelAmount", machine.fuel.toShort())
            .putShort("FuelTotal", (if (machine.fuel > 0) BREWS_PER_FUEL else 0).toShort())
        is Hopper -> data.putItems(machine.slots).putInt("TransferCooldown", machine.cooldown)
        is Dispenser -> data.putItems(machine.slots)
        is Crafter -> data.putItems(machine.slots).putShort("disabled_slots", machine.disabled.toShort())
        null -> {}
    }
    if (pos in comparators) data.putInt("OutputSignal", comparatorPower[pos] ?: 0)
    if (state.name == BEACON && "primary" !in data) data.putInt("primary", 0).putInt("secondary", 0)
    return data.build()
}

/** Each item in [container] as the game saves one, with its slot, the empty ones left out. */
private fun NbtMapBuilder.putItems(container: Container): NbtMapBuilder =
    putList("Items", NbtType.COMPOUND, container.contents().mapIndexedNotNull { slot, there -> there?.let { ItemStacks.nbtOf(it.stack, slot) } })

/**
 * Puts back what the block entity [data] keeps, as the game saved it,
 * once the chunk it's in is in the world, making its items with [items]:
 * a container's items and a machine's progress, kept apart from the rest
 * of its data, a comparator's power, a lectern's book, a piston's arm, and
 * that a daylight detector is there, for the sky to power it.
 * A block a piston was moving lands where it was going.
 */
internal fun FlatWorld.restoreBlockEntity(data: NbtMap, items: ItemStacks) {
    val pos = BlockPos(data.getInt("x"), data.getInt("y"), data.getInt("z"))
    val state = stateAt(pos)?.takeIf { it.name != AIR } ?: return
    when (data.getString("id")) {
        "MovingBlock" -> return landSaved(pos, data, items)
        "Comparator" -> {
            comparators += pos
            data.getInt("OutputSignal", 0).takeIf { it > 0 }?.let { comparatorPower[pos] = it }
            return
        }
        "PistonArm" -> if (state.isPiston) {
            val arm = pistonArm(pos, state)
            arm.extending = data.getByte("State", 0) in 1..2
            arm.progress = if (arm.extending) 1f else 0f
            arm.lastProgress = arm.progress
            setBlockEntity(pos, arm.data())
            return
        }
        "Beacon" -> beacons[pos] = 0
        "DaylightDetector" -> daylightDetectors += pos
        "Lectern" -> data.getCompound("book", null)?.let(items::fromNbt)?.let { (_, book) -> lecternAt(pos)[0] = SlotStack(book, newStackId()) }
    }
    val machine = furnaceAt(pos) ?: brewingStandAt(pos) ?: hopperAt(pos) ?: dispenserAt(pos) ?: crafterAt(pos)
    val container = if (state.storage != null && state.storage != Storage.ENDER_CHEST) containerAt(pos) else machine?.slots
    if (container != null) {
        for (saved in data.getList("Items", NbtType.COMPOUND, emptyList())) {
            val (slot, stack) = items.fromNbt(saved) ?: continue
            if (slot in 0 until container.size) container[slot] = SlotStack(stack, newStackId())
        }
    }
    when (machine) {
        is Furnace -> machine.restore(data.getShort("BurnTime", 0).toInt(), data.getShort("BurnDuration", 0).toInt(), data.getShort("CookTime", 0).toInt(), data.getInt("StoredXPInt", 0).toFloat())
        is BrewingStand -> machine.restore(data.getShort("CookTime", 0).toInt(), data.getShort("FuelAmount", 0).toInt())
        is Hopper -> machine.cooldown = data.getInt("TransferCooldown", 0)
        is Crafter -> machine.disabled = data.getShort("disabled_slots", 0).toInt() and 0x1FF
        else -> {}
    }
    setBlockEntity(pos, data.toBuilder().apply { SAVED_APART.forEach(::remove) }.build())
}

/** Lands the block a piston was moving to [pos] when the world was saved, as [data] has it, with whatever it kept. */
private fun FlatWorld.landSaved(pos: BlockPos, data: NbtMap, items: ItemStacks) {
    val moving = data.getCompound("movingBlock", NbtMap.EMPTY)
    val landed = blocks.find(moving.getString("name"), moving.getCompound("states", NbtMap.EMPTY)) ?: return
    setBlockEntity(pos, null)
    set(pos, landed)
    data.getCompound("movingEntity", null)?.takeIf { !landed.isPiston }?.let {
        restoreBlockEntity(it.toBuilder().putInt("x", pos.x).putInt("y", pos.y).putInt("z", pos.z).build(), items)
    }
}

/** The updates that wait on the power reaching a block, which [FlatWorld.redstoneDue] keeps from being due twice at once. */
private val POWER_UPDATES = setOf(
    BlockUpdate.TORCH, BlockUpdate.REPEATER, BlockUpdate.COMPARATOR, BlockUpdate.PISTON, BlockUpdate.OBSERVER, BlockUpdate.LAMP, BlockUpdate.LECTERN,
)

/**
 * The blocks due to be looked at again, as the game saves the ticks
 * they're due, by chunk: where each is, the block, and the block tick it's
 * due on. Those whose block has gone since are left out.
 */
internal fun FlatWorld.savedTicks(): Map<ChunkPos, List<NbtMap>> {
    val ticks = LinkedHashMap<Pair<BlockPos, Long>, NbtMap>()
    for ((tick, due) in scheduled) {
        for ((pos, update) in due) {
            val state = stateAt(pos)?.takeIf { update in it.dueFor } ?: continue
            ticks.getOrPut(pos to tick) { pendingTick(pos, state, tick) }
        }
    }
    return ticks.values.groupBy { BlockPos(it.getInt("x"), it.getInt("y"), it.getInt("z")).chunk }
}

/** The tick the block [state] at [pos] is due on [time], as the game saves one. */
private fun pendingTick(pos: BlockPos, state: BlockState, time: Long) = NbtMap.builder()
    .putInt("x", pos.x).putInt("y", pos.y).putInt("z", pos.z)
    .putLong("time", time)
    .putCompound("blockState", blockStateNbt(state))
    .build()

/**
 * Sets the blocks due ticks in [saved], a chunk's ticks as the game saves
 * them, to be looked at again as long after the chunk's back in the world
 * as they had to go, for what Kore looks at each for. A tick for a block
 * that's gone since goes too. Returns those for blocks Kore doesn't tick,
 * each with how long it had to go.
 */
internal fun FlatWorld.restoreTicks(saved: NbtMap): List<Pair<NbtMap, Long>> {
    val savedOn = saved.getInt("currentTick", 0).toLong()
    val others = mutableListOf<Pair<NbtMap, Long>>()
    for (tick in saved.getList("tickList", NbtType.COMPOUND, emptyList())) {
        val pos = BlockPos(tick.getInt("x"), tick.getInt("y"), tick.getInt("z"))
        val name = tick.getCompound("blockState", NbtMap.EMPTY).getString("name", "")
        val state = stateAt(pos)?.takeIf { it.name == name } ?: continue
        val wait = (tick.getLong("time", savedOn) - savedOn).coerceIn(1, Int.MAX_VALUE.toLong())
        val updates = state.dueFor
        if (updates.isEmpty()) others += tick to wait
        for (update in updates) {
            if (update in POWER_UPDATES) redstoneDue += pos
            schedule(pos, wait.toInt(), update)
        }
    }
    return others
}

// The game's names for the entities Kore keeps in the world.
private const val ITEM = "minecraft:item"
private const val ORB = "minecraft:xp_orb"
private const val FALLING = "minecraft:falling_block"

/** Where the game keeps the block a falling block is. */
private const val FALLING_STATE = "FallingBlock"

/** How much an orb is worth, as the game saves it. */
private const val ORB_VALUE = "experience value"

/**
 * The items, orbs and falling blocks in the world, as the game saves
 * entities, by the chunk each is in, each under the unique ID [uniqueId]
 * gives its number.
 */
internal fun FlatWorld.savedActors(uniqueId: (Long) -> Long = { it }): Map<ChunkPos, List<NbtMap>> {
    val items = items.map { item ->
        item.position.toBlockPos().chunk to actor(ITEM, uniqueId(item.id), item.position, item.velocity, item.onGround)
            .putCompound("Item", ItemStacks.nbtOf(item.stack, 0))
            .putShort("Age", item.age.toShort())
            .putShort("Health", ITEM_HEALTH)
            .putLong("PickupDelay", item.pickupDelay.toLong())
            .build()
    }
    val orbs = orbs.map { orb ->
        orb.position.toBlockPos().chunk to actor(ORB, uniqueId(orb.id), orb.position, orb.velocity, orb.onGround)
            .putInt(ORB_VALUE, orb.value)
            .putShort("Age", orb.age.toShort())
            .build()
    }
    val falling = fallingBlocks.map { falling ->
        falling.position.toBlockPos().chunk to actor(FALLING, uniqueId(falling.id), falling.position, falling.velocity, onGround = false)
            .putCompound(FALLING_STATE, blockStateNbt(falling.state))
            .putByte("Time", minOf(falling.age, Byte.MAX_VALUE.toInt()).toByte())
            .build()
    }
    return (items + orbs + falling).groupBy({ it.first }, { it.second })
}

/** [state] as the game saves a block's state: its name, its properties and the version it's from. */
internal fun blockStateNbt(state: BlockState): NbtMap =
    NbtMap.builder().putString("name", state.name).putCompound("states", state.properties).putInt("version", state.version).build()

/** The health the game gives an item lying in the world. */
private const val ITEM_HEALTH: Short = 5

/** The data every entity the game saves has: what kind it is, its number, where it is, how it moves, and whether it's landed. */
private fun actor(identifier: String, id: Long, position: Vec3, velocity: Vec3, onGround: Boolean) = NbtMap.builder()
    .putString("identifier", identifier)
    .putLong("UniqueID", id)
    .putList("Pos", NbtType.FLOAT, listOf(position.x, position.y, position.z))
    .putList("Motion", NbtType.FLOAT, listOf(velocity.x, velocity.y, velocity.z))
    .putList("Rotation", NbtType.FLOAT, listOf(0f, 0f))
    .putBoolean("OnGround", onGround)

/**
 * Puts the item, orb or falling block the game saved as [data] back in
 * the world, making an item's stack with [items]. Returns whether it was
 * one, as Kore keeps no other kind of entity.
 */
internal fun FlatWorld.restoreActor(data: NbtMap, items: ItemStacks): Boolean {
    val position = data.getList("Pos", NbtType.FLOAT, emptyList()).takeIf { it.size == 3 }?.let { Vec3(it[0], it[1], it[2]) } ?: return false
    val velocity = data.getList("Motion", NbtType.FLOAT, emptyList()).takeIf { it.size == 3 }?.let { Vec3(it[0], it[1], it[2]) } ?: Vec3(0f, 0f, 0f)
    when (data.getString("identifier")) {
        ITEM -> {
            val (_, stack) = items.fromNbt(data.getCompound("Item", NbtMap.EMPTY)) ?: return true
            val item = spawnItem(stack, position, velocity, data.getLong("PickupDelay", 0).toInt().coerceAtLeast(0))
            item.age = data.getShort("Age", 0).toInt()
        }
        ORB -> {
            val value = data.getInt(ORB_VALUE, data.getInt("Value", 0)).takeIf { it > 0 } ?: return true
            spawnOrb(value, position, velocity).age = data.getShort("Age", 0).toInt()
        }
        FALLING -> {
            val saved = data.getCompound(FALLING_STATE, NbtMap.EMPTY)
            val state = blocks.find(saved.getString("name", ""), saved.getCompound("states", NbtMap.EMPTY)) ?: return true
            spawnFallingBlock(state, position, position.toBlockPos().y, velocity).age = data.getByte("Time", 0).toInt()
        }
        else -> return false
    }
    return true
}
