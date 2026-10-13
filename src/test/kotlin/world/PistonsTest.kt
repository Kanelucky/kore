package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.inventory.SlotStack
import org.koremc.registry.AIR
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import org.koremc.registry.ItemDefinition
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemVersion
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PistonsTest {
    private fun state(name: String, vararg properties: Pair<String, Any>) = BlockState(name, NbtMap.fromMap(mapOf(*properties)), version = 1)

    private val air = state(AIR)
    private val stone = state("minecraft:stone")
    private val obsidian = state("minecraft:obsidian")
    private val slime = state("minecraft:slime")
    private val torch = state("minecraft:torch", TORCH_FACING to "top")
    private val movingBlock = state("minecraft:moving_block")
    private val levers = (0..1).map { on -> state("minecraft:lever", "lever_direction" to "up_east_west", "open_bit" to on.toByte()) }
    private val observers = listOf(false, true).map { state(OBSERVER, "minecraft:facing_direction" to "up", "powered_bit" to bit(it)) }
    private val westObservers = listOf(false, true).map { state(OBSERVER, "minecraft:facing_direction" to "west", "powered_bit" to bit(it)) }
    // Facing east, so a chest pairs with one north or south of it.
    private val chest = state("minecraft:chest", CARDINAL to "east")
    private val furnace = state("minecraft:furnace", CARDINAL to "east")
    private val shulkerBox = state("minecraft:white_shulker_box")
    private val enderChest = state("minecraft:ender_chest", CARDINAL to "east")
    private val eastObservers = listOf(false, true).map { state(OBSERVER, "minecraft:facing_direction" to "east", "powered_bit" to bit(it)) }

    /** Facings as pistons store them, sideways the wrong way round. */
    private fun stored(facing: Face) = if (facing.horizontal) facing.opposite.ordinal else facing.ordinal
    private fun piston(facing: Face, sticky: Boolean = false) = state(if (sticky) STICKY_PISTON else PISTON, FACING_DIRECTION to stored(facing))
    private fun head(facing: Face, sticky: Boolean = false) =
        state(if (sticky) "minecraft:sticky_piston_arm_collision" else "minecraft:piston_arm_collision", FACING_DIRECTION to stored(facing))

    private val palette = BlockPalette(
        listOf(air, stone, obsidian, slime, torch, movingBlock, chest, furnace, shulkerBox, enderChest) + levers + observers + westObservers + eastObservers +
            Face.entries.flatMap { facing -> listOf(false, true).flatMap { sticky -> listOf(piston(facing, sticky), head(facing, sticky)) } },
    )

    // One layer of stone at the bottom of the world: blocks go from y -63 up.
    private val world = FlatWorld(palette, listOf(stone))
    private val at = BlockPos(0, -63, 0)
    private val lever = BlockPos(0, -63, 1)

    private val broken = mutableListOf<BlockPos>()

    /** Ticks the world's blocks and power [times]. */
    private fun tick(times: Int = 1) = repeat(times) {
        broken += world.tickBlocks().broken.keys
        world.updatePower()
    }

    private fun east(x: Int) = BlockPos(x, -63, 0)

    private fun placePiston(facing: Face = Face.EAST, sticky: Boolean = false) {
        world[at] = piston(facing, sticky)
        world.updatePower()
    }

    /** The motions the pistons give players standing at [feet], each by their place in the list, a tick at a time for [ticks] ticks. */
    private fun motions(ticks: Int, vararg feet: Vec3): List<Map<Long, Vec3>> = (1..ticks).map {
        tick()
        world.pistonMotions(feet.withIndex().associate { (i, at) -> i.toLong() to at })
    }

    private val up = Vec3(0f, 0.5f, 0f)

    private val cobblestone = ItemDefinition("minecraft:cobblestone", 1, componentBased = false, ItemVersion.NONE)

    /** Ticks the world's blocks, items, orbs and power [times], as the server does. */
    private fun tickAll(times: Int = 1) = repeat(times) {
        world.tickBlocks()
        world.tickItems()
        world.tickOrbs(emptyList())
        world.updatePower()
    }

    /** Switches the lever beside the piston, and waits for the piston to finish moving. */
    private fun switch(on: Boolean) {
        world[lever] = levers[if (on) 1 else 0]
        world.updatePower()
        tick(5)
    }

    @Test
    fun `a piston pushes the blocks in front of it, two ticks after power reaches it, and they slide out over two more, as in Bedrock`() {
        placePiston()
        world[east(1)] = stone
        world[east(2)] = stone
        world[lever] = levers[1]
        world.updatePower()

        tick(2)
        assertEquals(head(Face.EAST), world.stateAt(east(1)))
        assertEquals(listOf(movingBlock, movingBlock), listOf(world.stateAt(east(2)), world.stateAt(east(3))))
        assertEquals(1.toByte(), world.blockEntityAt(at)?.getByte("State")) // Moving out
        assertEquals("minecraft:stone", world.blockEntityAt(east(3))?.getCompound("movingBlock")?.getString("name"))

        val progress = (1..2).map { tick(); world.blockEntityAt(at)!!.getFloat("Progress") to world.blockEntityAt(at)!!.getFloat("LastProgress") }
        assertEquals(listOf(0.5f to 0f, 1f to 1f), progress)
        assertEquals(2.toByte(), world.blockEntityAt(at)?.getByte("State"))
        assertEquals(listOf(stone, stone), listOf(world.stateAt(east(2)), world.stateAt(east(3))))
        assertNull(world.blockEntityAt(east(3)))
    }

    @Test
    fun `a piston pulls its head back in, and a sticky one the block in front of it too`() {
        placePiston()
        world[east(1)] = stone
        switch(on = true)
        switch(on = false)
        assertEquals(air, world.stateAt(east(1)))
        assertEquals(stone, world.stateAt(east(2)))
        assertEquals(0.toByte(), world.blockEntityAt(at)?.getByte("State"))

        placePiston(sticky = true)
        world[east(1)] = stone
        world[east(2)] = air
        switch(on = true)
        assertEquals(head(Face.EAST, sticky = true), world.stateAt(east(1)))
        switch(on = false)
        assertEquals(stone, world.stateAt(east(1)))
        assertEquals(air, world.stateAt(east(2)))
    }

    @Test
    fun `twelve blocks move, thirteen don't, and obsidian doesn't`() {
        placePiston()
        for (x in 1..12) world[east(x)] = stone
        switch(on = true)
        assertEquals(stone, world.stateAt(east(13)))

        switch(on = false)
        world[east(14)] = stone
        world[east(1)] = stone
        switch(on = true)
        assertEquals(stone, world.stateAt(east(1)))

        world[at] = air
        placePiston(Face.UP)
        world[BlockPos(0, -62, 0)] = obsidian
        switch(on = true)
        assertEquals(obsidian, world.stateAt(BlockPos(0, -62, 0)))
    }

    @Test
    fun `a piston breaks what it can't move but breaks, like a torch`() {
        placePiston()
        world[east(1)] = stone
        world[east(2)] = torch
        switch(on = true)
        assertEquals(listOf(east(2)), broken)
        assertEquals(stone, world.stateAt(east(2)))
    }

    @Test
    fun `slime drags the blocks beside it along, and on the ground, the ground, which won't move`() {
        placePiston()
        world[east(1)] = slime
        switch(on = true)
        assertEquals(slime, world.stateAt(east(1)))

        // A block up, off the ground, it moves, and the block on top of it with it.
        world[lever] = levers[0]
        world[at] = air
        world[east(1)] = air
        val raised = BlockPos(0, -62, 0)
        world[raised] = piston(Face.EAST)
        world[BlockPos(1, -62, 0)] = slime
        world[BlockPos(1, -61, 0)] = stone
        world[BlockPos(0, -62, 1)] = levers[1]
        world.updatePower()
        tick(5)
        assertEquals(slime, world.stateAt(BlockPos(2, -62, 0)))
        assertEquals(stone, world.stateAt(BlockPos(2, -61, 0)))
        assertEquals(air, world.stateAt(BlockPos(1, -61, 0)))
    }

    @Test
    fun `an observer a piston moves pulses once it lands, as in Bedrock`() {
        placePiston()
        world[east(1)] = observers[0]
        switch(on = true)
        assertEquals(observers[0], world.stateAt(east(2))) // Landed, and waiting to pulse
        tick(2)
        assertEquals(observers[1], world.stateAt(east(2)))
    }

    @Test
    fun `an observer's pulse pushes a piston out, though it's over before the piston moves, which then pulls back in`() {
        placePiston()
        // Behind the piston, watching west, its back into the piston.
        world[east(-1)] = westObservers[0]
        world.updatePower()
        world[east(-2)] = stone
        world.updatePower()

        val out = (1..15).map { tick(); world.stateAt(east(1)) == head(Face.EAST) }
        assertTrue(out.any { it })
        assertEquals(air, world.stateAt(east(1)))
    }

    @Test
    fun `a flying machine flies, its observers pulsing as they land, though one's pulled back mid-pulse`() {
        // Up in the air, two halves, one pushing the other along, and the other pulling it after.
        fun up(x: Int, z: Int) = BlockPos(x, -58, z)
        val trigger = up(0, 2)
        world[trigger] = stone
        world[up(1, 2)] = westObservers[0]
        world[up(2, 2)] = piston(Face.EAST, sticky = true)
        world[up(3, 2)] = slime
        world[up(4, 2)] = slime
        world[up(1, 1)] = slime
        world[up(2, 1)] = slime
        world[up(3, 1)] = piston(Face.WEST)
        world[up(4, 1)] = eastObservers[0]
        world.updatePower()
        tick(10)

        world[trigger] = air
        world.updatePower()

        // Where the sticky piston is each tick it isn't moving, which goes west a block at a time.
        val stops = (1..160).mapNotNull { tick(); (-50..2).firstOrNull { world.stateAt(up(it, 2))?.name == STICKY_PISTON } }.distinct()
        assertEquals((2 downTo stops.last()).toList(), stops)
        assertTrue(stops.last() < -5, "the sticky piston got to x ${stops.last()}")
    }

    @Test
    fun `a piston lifts the player on what it pushes up, half a block a tick, a tick ahead of the arm, and a sticky one carries them back down`() {
        placePiston(Face.UP, sticky = true)
        world[BlockPos(0, -62, 0)] = stone
        val aside = Vec3(2.5f, -63f, 0.5f)
        world[lever] = levers[1]
        world.updatePower()
        assertEquals(listOf(emptyMap(), mapOf(0L to up), mapOf(0L to up), emptyMap()), motions(4, Vec3(0.5f, -61f, 0.5f), aside))

        world[lever] = levers[0]
        world.updatePower()
        val down = Vec3(0f, -0.5f, 0f)
        assertEquals(listOf(emptyMap(), mapOf(0L to down), mapOf(0L to down), emptyMap()), motions(4, Vec3(0.5f, -60f, 0.5f), aside))
    }

    @Test
    fun `a piston's head lifts the player standing on the piston, and takes them back down with it`() {
        placePiston(Face.UP)
        world[lever] = levers[1]
        world.updatePower()
        assertEquals(listOf(emptyMap(), mapOf(0L to up), mapOf(0L to up), emptyMap()), motions(4, Vec3(0.5f, -62f, 0.5f)))

        world[lever] = levers[0]
        world.updatePower()
        val down = Vec3(0f, -0.5f, 0f)
        assertEquals(listOf(emptyMap(), mapOf(0L to down), mapOf(0L to down), emptyMap()), motions(4, Vec3(0.5f, -61f, 0.5f)))
    }

    @Test
    fun `a piston's head pulling back in sideways slides out from under the player standing on it`() {
        placePiston()
        switch(on = true)
        world[lever] = levers[0]
        world.updatePower()
        assertEquals(List(4) { emptyMap() }, motions(4, Vec3(1.5f, -62f, 0.5f)))
    }

    @Test
    fun `a piston pushes a player in the way of what it pushes sideways as far as it goes beyond where it meets them, as soon as it can, and carries one standing on it`() {
        placePiston()
        world[east(1)] = stone
        world[lever] = levers[1]
        world.updatePower()
        val onTop = Vec3(1.5f, -62f, 0.5f)
        // A player's back is 0.3 blocks behind where they stand: up against the stone, and only just in its way.
        val against = Vec3(2.3f, -63f, 0.5f)
        val justInTheWay = Vec3(2.9f, -63f, 0.5f)
        val behind = Vec3(-1.5f, -63f, 0.5f)
        // East, in thousandths of a block a tick.
        val pushes = motions(4, onTop, against, justInTheWay, behind).map { tick -> tick.mapValues { (_, motion) -> (motion.x * 1000).roundToInt() } }
        assertEquals(listOf(emptyMap(), mapOf(0L to 500, 1L to 500, 2L to 400), mapOf(0L to 500, 1L to 500), emptyMap()), pushes)
    }

    @Test
    fun `a piston lifts a player jumping over what it pushes up only as far as it goes beyond where it meets them`() {
        placePiston(Face.UP)
        world[BlockPos(0, -62, 0)] = stone
        world[lever] = levers[1]
        world.updatePower()
        val pushes = motions(4, Vec3(0.5f, -60.7f, 0.5f)).map { tick -> tick.mapValues { (_, motion) -> (motion.y * 1000).roundToInt() } }
        assertEquals(listOf(emptyMap(), mapOf(0L to 500), mapOf(0L to 200), emptyMap()), pushes)
    }

    @Test
    fun `the client would push a player inside a block a piston's moving, or a piston's head, up out of it, but not out of others`() {
        placePiston(Face.UP)
        world[BlockPos(0, -62, 0)] = stone
        world[lever] = levers[1]
        world.updatePower()
        tick(2)
        val onTop = Vec3(0.5f, -61f, 0.5f)
        assertTrue(world.snapsUp(BlockPos(0, -61, 0), onTop)) // Moving into them
        assertFalse(world.snapsUp(BlockPos(0, -62, 0), onTop)) // The head, which they're only standing on
        assertTrue(world.snapsUp(BlockPos(0, -62, 0), Vec3(0.5f, -62f, 0.5f)))
        assertFalse(world.snapsUp(BlockPos(0, -61, 0), Vec3(2.5f, -63f, 0.5f)))

        // Drawn from where it started, under the head, sliding up as a block pulled down there would slide up from it.
        val (from, data) = world.movingFrom(BlockPos(0, -61, 0), onTop)!!
        assertEquals(BlockPos(0, -62, 0), from)
        assertEquals(listOf(0, -62, 0, 0), listOf(data.getInt("x"), data.getInt("y"), data.getInt("z"), data.getByte("expanding").toInt()))
        tick(2)
        assertFalse(world.snapsUp(BlockPos(0, -61, 0), onTop)) // Landed
        // Out, the head's in the way of a player as any block is, even one the server last saw inside it.
        assertFalse(world.snapsUp(BlockPos(0, -62, 0), Vec3(0.5f, -62f, 0.5f)))
    }

    @Test
    fun `a moving block isn't drawn from where it started if another moves in there`() {
        placePiston(Face.UP)
        world[BlockPos(0, -62, 0)] = stone
        world[BlockPos(0, -61, 0)] = stone
        world[lever] = levers[1]
        world.updatePower()
        tick(2)
        assertNull(world.movingFrom(BlockPos(0, -60, 0), Vec3(0.5f, -60f, 0.5f)))
        assertEquals(BlockPos(0, -62, 0), world.movingFrom(BlockPos(0, -61, 0), Vec3(2.5f, -63f, 0.5f))?.first) // Only the head moved in under it
    }

    @Test
    fun `an item on a block a piston pushes up rises with it, half a block a tick, seen there at once, and lies on it where it lands`() {
        placePiston(Face.UP)
        world[BlockPos(0, -62, 0)] = stone
        val item = world.spawnItem(ItemStack(cobblestone), Vec3(0.5f, -61f, 0.5f), Vec3(0f, 0f, 0f), 0)
        tickAll(5)
        world[lever] = levers[1]
        world.updatePower()

        val heights = (1..6).map { tickAll(); item.position.y to item.snapped }
        assertEquals(listOf(-61f to false, -61f to false, -60.5f to true, -60f to true, -60f to false, -60f to false), heights)
        assertEquals(stone, world.stateAt(BlockPos(0, -61, 0)))
    }

    @Test
    fun `an item on a piston's head falls as it pulls back in, rather than going down with it`() {
        placePiston(Face.UP)
        switch(on = true)
        val item = world.spawnItem(ItemStack(cobblestone), Vec3(0.5f, -61f, 0.5f), Vec3(0f, 0f, 0f), 0)
        tickAll(5)
        world[lever] = levers[0]
        world.updatePower()

        val heights = (1..20).map { tickAll(); item.position.y }
        // The head goes, and the item falls from where it was, slower at first than the head goes.
        assertTrue(heights[3] > -61.5f, "it's at ${heights[3]}")
        assertEquals(-62f, heights.last())
        assertTrue(heights.zipWithNext().all { (a, b) -> b <= a })
    }

    @Test
    fun `a block a piston pushes sideways carries the item on it, and pushes an orb in its way as far as it goes beyond it`() {
        placePiston()
        world[east(1)] = stone
        val item = world.spawnItem(ItemStack(cobblestone), Vec3(1.5f, -62f, 0.5f), Vec3(0f, 0f, 0f), 0)
        // An orb's a quarter of a block across, so 0.375 blocks from the stone.
        val orb = world.spawnOrb(1, Vec3(2.5f, -63f, 0.5f), Vec3(0f, 0f, 0f))
        tickAll(5)
        world[lever] = levers[1]
        world.updatePower()
        tickAll(6)

        assertEquals(stone, world.stateAt(east(2)))
        assertEquals(Vec3(2.5f, -62f, 0.5f), item.position)
        assertEquals(Vec3(3.125f, -63f, 0.5f), orb.position)
    }

    @Test
    fun `an item lands on a piston's head`() {
        placePiston(Face.UP)
        switch(on = true)
        val item = world.spawnItem(ItemStack(cobblestone), Vec3(0.5f, -60f, 0.5f), Vec3(0f, 0f, 0f), 0)
        repeat(20) { world.tickItems() }
        assertEquals(-61f, item.position.y)
    }

    @Test
    fun `a piston moves a chest with what's in it, and a furnace with what it's smelting, as in Bedrock`() {
        placePiston()
        world[east(1)] = chest
        world.containerAt(east(1))[0] = SlotStack(ItemStack(cobblestone, 5), 1)
        switch(on = true)
        assertEquals(chest, world.stateAt(east(2)))
        assertEquals(5, world.containerAt(east(2))[0]?.stack?.count)

        switch(on = false)
        world[east(2)] = air
        world[east(1)] = furnace
        val oven = world.furnaceAt(east(1))!!
        oven.slots[FURNACE_INPUT] = SlotStack(ItemStack(cobblestone, 3), 2)
        switch(on = true)
        assertEquals(furnace, world.stateAt(east(2)))
        assertSame(oven, world.furnaceAt(east(2)))
    }

    @Test
    fun `a chest moves drawn as a chest, and lands with its own block entity`() {
        placePiston()
        world[east(1)] = chest
        world[lever] = levers[1]
        world.updatePower()
        tick(2)
        val moving = world.blockEntityAt(east(2))!!
        assertEquals("Chest", moving.getCompound("movingEntity").getString("id"))
        assertEquals(listOf(2, -63, 0), moving.getCompound("movingEntity").let { listOf(it.getInt("x"), it.getInt("y"), it.getInt("z")) })
    }

    @Test
    fun `a double chest a piston moves half of comes apart, as in Bedrock`() {
        placePiston()
        val partner = BlockPos(1, -63, -1)
        world[east(1)] = chest
        world[partner] = chest
        world.pairChest(partner)
        world.containerAt(east(1))[0] = SlotStack(ItemStack(cobblestone, 5), 1)
        switch(on = true)

        assertFalse(world.blockEntityAt(east(2))?.containsKey(PAIR_X) == true)
        assertFalse(world.blockEntityAt(partner)?.containsKey(PAIR_X) == true)
        assertEquals(5, world.containerAt(east(2))[0]?.stack?.count)
    }

    @Test
    fun `a piston breaks a shulker box, and won't move an ender chest`() {
        placePiston()
        world[east(1)] = shulkerBox
        switch(on = true)
        assertEquals(listOf(east(1)), broken)

        switch(on = false)
        world[east(1)] = enderChest
        switch(on = true)
        assertEquals(enderChest, world.stateAt(east(1)))
    }

    @Test
    fun `a piston that's out and its head break together`() {
        placePiston()
        switch(on = true)
        val changes = world.breakBlock(east(1))
        assertTrue(at in changes.broken)
        assertEquals(air, world.stateAt(at))
    }
}
