package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.inventory.SlotStack
import org.koremc.registry.AIR
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import org.koremc.registry.CreativeInventory
import org.koremc.registry.ItemDefinition
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemStacks
import org.koremc.registry.ItemVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavingTest {
    private fun state(name: String, vararg properties: Pair<String, Any>) = BlockState(name, NbtMap.fromMap(mapOf(*properties)), version = 18168865)

    private val stone = state("minecraft:stone")
    private val shulkerBox = state("minecraft:white_shulker_box")
    private val hopper = state(HOPPER, "facing_direction" to 0, "toggle_bit" to 0.toByte())
    private val brewingStand = state(BREWING_STAND, "brewing_stand_slot_a_bit" to 0.toByte(), "brewing_stand_slot_b_bit" to 0.toByte(), "brewing_stand_slot_c_bit" to 0.toByte())
    private val comparator = state(UNPOWERED_COMPARATOR, CARDINAL to "south", "output_lit_bit" to 0.toByte(), SUBTRACTING to 0.toByte())
    private val piston = state(PISTON, "facing_direction" to 1)
    private val movingBlock = state("minecraft:moving_block")
    private val lectern = state(LECTERN, CARDINAL to "south", "powered_bit" to 0.toByte())
    private val palette = BlockPalette(listOf(state(AIR), stone, shulkerBox, hopper, brewingStand, comparator, piston, movingBlock, lectern))

    private val coal = ItemDefinition("minecraft:coal", 1, componentBased = false, ItemVersion.NONE)
    private val book = ItemDefinition(WRITTEN_BOOK, 2, componentBased = false, ItemVersion.NONE, maxStackSize = 16)
    private val items = ItemStacks(listOf(coal, book), CreativeInventory(emptyList(), emptyList()), palette)

    private fun world() = FlatWorld(palette, listOf(stone))
    private val at = BlockPos(1, -63, 1)

    /** [from]'s block entities, as saved, put back in a world with the same blocks, which it returns. */
    private fun reloaded(from: FlatWorld, vararg positions: BlockPos): FlatWorld {
        val saved = from.savedBlockEntities()
        val into = world()
        for (pos in positions) into[pos] = from.stateAt(pos)!!
        saved.values.flatten().forEach { into.restoreBlockEntity(it, items) }
        return into
    }

    @Test
    fun `shulker boxes and hoppers keep their items, a hopper its cooldown and a brewing stand its brew, all saved with the game's names`() {
        val world = world()
        val hopperAt = at.copy(x = 3)
        val standAt = at.copy(x = 5)
        world[at] = shulkerBox
        world.containerAt(at)[26] = SlotStack(ItemStack(coal, 9), world.newStackId())
        world[hopperAt] = hopper
        world.hopperAt(hopperAt)!!.apply {
            slots[1] = SlotStack(ItemStack(coal), world.newStackId())
            cooldown = 4
        }
        world[standAt] = brewingStand
        world.brewingStandAt(standAt)!!.restore(brewTime = 120, fuel = 17)

        val saved = world.savedBlockEntities().values.flatten().associateBy { it.getString("id") }
        assertEquals(setOf("ShulkerBox", "Hopper", "BrewingStand"), saved.keys)
        assertEquals(listOf(4, 120, 17), listOf(saved.getValue("Hopper").getInt("TransferCooldown"), saved.getValue("BrewingStand").getShort("CookTime").toInt(), saved.getValue("BrewingStand").getShort("FuelAmount").toInt()))

        val loaded = reloaded(world, at, hopperAt, standAt)
        assertEquals(ItemStack(coal, 9), loaded.containerAt(at)[26]?.stack)
        assertEquals(ItemStack(coal), loaded.hopperAt(hopperAt)!!.slots[1]?.stack)
        assertEquals(4, loaded.hopperAt(hopperAt)!!.cooldown)
        assertEquals(listOf(120, 17), loaded.brewingStandAt(standAt)!!.let { listOf(it.brewTime, it.fuel) })
    }

    @Test
    fun `a comparator keeps the power it gives, a piston its arm out, and a lectern its book`() {
        val world = world()
        val pistonAt = at.copy(x = 3)
        val lecternAt = at.copy(x = 5)
        world[at] = comparator
        world.comparatorPower[at] = 9
        world[pistonAt] = piston
        world.pistonArm(pistonAt, piston).apply {
            extending = true
            progress = 1f
        }
        world.setBlockEntity(pistonAt, world.pistonArm(pistonAt, piston).data())
        world[lecternAt] = lectern
        world.putOnLectern(lecternAt, ItemStack(book))

        val loaded = reloaded(world, at, pistonAt, lecternAt)
        assertTrue(at in loaded.comparators)
        assertEquals(9, loaded.comparatorPower[at])
        assertNull(loaded.blockEntityAt(at), "the comparator's power is kept apart")
        assertTrue(loaded.pistonArm(pistonAt, piston).extending)
        assertEquals(1f, loaded.pistonArm(pistonAt, piston).progress)
        assertEquals(ItemStack(book), loaded.lecternBook(lecternAt))
    }

    @Test
    fun `a block a piston was moving when the world was saved lands where it was going`() {
        val world = world()
        world[at] = movingBlock
        val data = NbtMap.builder()
            .putString("id", "MovingBlock")
            .putCompound("movingBlock", NbtMap.builder().putString("name", shulkerBox.name).putCompound("states", NbtMap.EMPTY).putInt("version", 1).build())
            .putCompound(
                "movingEntity",
                NbtMap.builder().putString("id", "ShulkerBox").putList("Items", org.cloudburstmc.nbt.NbtType.COMPOUND, listOf(ItemStacks.nbtOf(ItemStack(coal, 2), 0))).build(),
            )
            .putInt("x", at.x).putInt("y", at.y).putInt("z", at.z)
            .build()

        world.restoreBlockEntity(data, items)

        assertEquals(shulkerBox, world.stateAt(at))
        assertEquals(ItemStack(coal, 2), world.containerAt(at)[0]?.stack)
    }

    @Test
    fun `blocks due a tick save as the game's ticks, and are due again as long after they're back, those Kore doesn't tick kept aside`() {
        val world = world()
        world[at] = comparator
        world.blockTicks = 100
        world.redstoneDue += at
        world.schedule(at, 2, BlockUpdate.COMPARATOR)
        // Nothing holds up stone, so a tick to see if it can stay, left from something else there, isn't saved.
        world.schedule(at.copy(x = 3), 1, BlockUpdate.SUPPORT)
        world[at.copy(x = 3)] = stone

        val tick = world.savedTicks().getValue(at.chunk).single()
        assertEquals(listOf(at.x, at.y, at.z), listOf(tick.getInt("x"), tick.getInt("y"), tick.getInt("z")))
        assertEquals(102L, tick.getLong("time"))
        assertEquals(UNPOWERED_COMPARATOR, tick.getCompound("blockState").getString("name"))

        fun gameTick(pos: BlockPos, name: String, time: Long) = NbtMap.builder()
            .putInt("x", pos.x).putInt("y", pos.y).putInt("z", pos.z)
            .putLong("time", time)
            .putCompound("blockState", NbtMap.builder().putString("name", name).putCompound("states", NbtMap.EMPTY).build())
            .build()
        // The ground's stone, which Kore doesn't tick, and a shulker box that's gone.
        val ground = gameTick(BlockPos(5, MIN_Y, 5), stone.name, 140)
        val gone = gameTick(BlockPos(6, -63, 6), shulkerBox.name, 120)
        val saved = NbtMap.builder().putInt("currentTick", 100).putList("tickList", org.cloudburstmc.nbt.NbtType.COMPOUND, listOf(tick, ground, gone)).build()

        val loaded = world()
        loaded[at] = comparator
        loaded.blockTicks = 7
        val others = loaded.restoreTicks(saved)

        assertEquals(listOf(ground to 40L), others)
        // A comparator stands on what's under it, so it's due to see if it can still stay too, which it can.
        assertEquals(mapOf(9L to setOf(at to BlockUpdate.SUPPORT, at to BlockUpdate.COMPARATOR)), loaded.scheduled)
        assertTrue(at in loaded.redstoneDue)
    }

    @Test
    fun `a saved tick is for what the block does by itself, and for one something holds up, whether it can still stay`() {
        val button = state("minecraft:stone_button", "button_pressed_bit" to 1.toByte(), "facing_direction" to 1)
        assertEquals(listOf(BlockUpdate.SUPPORT, BlockUpdate.BUTTON), button.dueFor)
        assertEquals(listOf(BlockUpdate.SUPPORT, BlockUpdate.COMPARATOR), comparator.dueFor)
        assertEquals(listOf(BlockUpdate.LECTERN), lectern.dueFor)
        assertEquals(emptyList(), stone.dueFor)
    }

    @Test
    fun `items and orbs save as the game's entities, under the unique IDs given them, and come back where they were`() {
        val world = world()
        world.spawnItem(ItemStack(coal, 4), Vec3(20.5f, -63f, 3.5f), Vec3(0f, 0f, 0f), pickupDelay = 5).age = 100
        world.spawnOrb(3, Vec3(1f, -63f, 1f), Vec3(0f, 0.1f, 0f))

        val saved = world.savedActors { it + 1000 }
        assertEquals(setOf(ChunkPos(1, 0), ChunkPos(0, 0)), saved.keys)
        val item = saved.getValue(ChunkPos(1, 0)).single()
        assertEquals(listOf("minecraft:item", "1001"), listOf(item.getString("identifier"), item.getLong("UniqueID").toString()))

        val loaded = world()
        saved.values.flatten().forEach { assertTrue(loaded.restoreActor(it, items)) }
        assertEquals(ItemStack(coal, 4), loaded.items.single().stack)
        assertEquals(Vec3(20.5f, -63f, 3.5f), loaded.items.single().position)
        assertEquals(100, loaded.items.single().age)
        assertEquals(3, loaded.orbs.single().value)
        assertEquals(false, loaded.restoreActor(NbtMap.builder().putString("identifier", "minecraft:pig").putList("Pos", org.cloudburstmc.nbt.NbtType.FLOAT, listOf(0f, 0f, 0f)).build(), items))
    }
}
