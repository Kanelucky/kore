package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.registry.BlockState
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemStacks

// What a frame keeps: the item in it, which way it's turned, in degrees, and the chance it drops when knocked out.
private const val ITEM = "Item"
private const val ROTATION = "ItemRotation"
private const val DROP_CHANCE = "ItemDropChance"

/** How far a click turns an item in a frame, of the eight ways it can be. */
private const val TURN = 45f

/** Whether a frame shows a map, filling the frame, rather than an item. */
private const val SHOWS_MAP = "item_frame_map_bit"

/** Whether this is an item frame, or a glow item frame. */
val BlockState.isItemFrame get() = name == "minecraft:frame" || name == "minecraft:glow_frame"

/** The block entity of a new, empty frame, [state]. */
internal fun frameData(state: BlockState): NbtMap =
    NbtMap.builder().putString("id", if (state.name == "minecraft:glow_frame") "GlowItemFrame" else "ItemFrame").build()

/** The item in the frame at [pos], made with [items], or null if it's empty. */
fun FlatWorld.frameItem(pos: BlockPos, items: ItemStacks): ItemStack? =
    blockEntityAt(pos)?.getCompound(ITEM, null)?.let(items::fromNbt)?.second

/** Which of the eight ways the item in the frame at [pos] is turned, from 0, or null if it's empty. */
internal fun FlatWorld.frameTurns(pos: BlockPos): Int? {
    val frame = blockEntityAt(pos)?.takeIf { it.containsKey(ITEM) } ?: return null
    return Math.floorMod(Math.round(frame.getFloat(ROTATION, 0f) / TURN), 8)
}

/**
 * Uses [held] on the frame at [pos], [state], as a player clicking it does:
 * into an empty frame goes one of [held], a map filling the frame, and the
 * item in a frame turns, an eighth of the way round, a click at a time.
 * Null for an empty frame clicked with nothing.
 */
fun FlatWorld.useFrame(pos: BlockPos, state: BlockState, held: ItemStack?): ItemUsedOn? {
    val frame = blockEntityAt(pos) ?: frameData(state)
    if (frame.containsKey(ITEM)) {
        val turned = (frame.getFloat(ROTATION, 0f) + TURN) % 360
        setBlockEntity(pos, frame.toBuilder().putFloat(ROTATION, turned).build())
        powerChanges += pos
        return ItemUsedOn(listOf(pos), BlockSound(pos, "block.itemframe.rotate_item", -1))
    }
    held ?: return null
    val showsMap = held.item.name == FILLED_MAP
    if (showsMap) blocks.find(state.name, state.properties + (SHOWS_MAP to bit(true)))?.let { set(pos, it) }
    setBlockEntity(pos, frame.toBuilder().putCompound(ITEM, itemData(held)).putFloat(ROTATION, 0f).putFloat(DROP_CHANCE, 1f).build())
    powerChanges += pos
    return ItemUsedOn(listOf(pos), BlockSound(pos, "block.itemframe.add_item", -1), HeldAfter.USED_UP)
}

/** One of [stack], as a frame keeps it: as the game saves an item, without a slot. */
private fun itemData(stack: ItemStack): NbtMap = ItemStacks.nbtOf(stack.copy(count = 1), 0).toBuilder().apply { remove("Slot") }.build()

/**
 * Knocks the item out of the frame at [pos], as hitting it does, made with
 * [items], leaving it empty, a frame that showed a map back to a frame.
 * Returns the item, or null if the frame was empty.
 */
fun FlatWorld.takeFromFrame(pos: BlockPos, items: ItemStacks): ItemStack? {
    val state = stateAt(pos)?.takeIf { it.isItemFrame } ?: return null
    val item = frameItem(pos, items) ?: return null
    if (state.properties[SHOWS_MAP] == 1.toByte()) blocks.find(state.name, state.properties + (SHOWS_MAP to bit(false)))?.let { set(pos, it) }
    setBlockEntity(pos, frameData(state))
    powerChanges += pos
    return item
}
