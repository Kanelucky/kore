package org.koremc.inventory

import org.cloudburstmc.nbt.NbtMap
import org.koremc.protocol.ContainerChange
import org.koremc.protocol.ContainerName
import org.koremc.protocol.SlotChange
import org.koremc.protocol.SlotRef
import org.koremc.protocol.StackAction
import org.koremc.protocol.StackRequest
import org.koremc.protocol.StackResponse
import org.koremc.registry.CreativeCategory
import org.koremc.registry.CreativeGroup
import org.koremc.registry.CreativeInventory
import org.koremc.registry.CreativeItem
import org.koremc.registry.ItemDefinition
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StackRequestHandlerTest {
    private val stick = ItemDefinition("minecraft:stick", 323, componentBased = false, ItemVersion.NONE)
    private val sword = ItemDefinition(
        "minecraft:diamond_sword", 335, componentBased = true, ItemVersion.DATA_DRIVEN,
        NbtMap.builder().putCompound("components", NbtMap.builder().putCompound("item_properties", NbtMap.builder().putInt("max_stack_size", 1).build()).build()).build(),
    )
    private val creative = CreativeInventory(
        groups = listOf(CreativeGroup(CreativeCategory.ITEMS, "", icon = null)),
        items = listOf(CreativeItem(ItemStack(stick), group = 0), CreativeItem(ItemStack(sword), group = 0)),
    )

    private val inventory = PlayerInventory()
    private var creativeMode = true
    private val handler = StackRequestHandler(inventory, creative) { creativeMode }

    @Test
    fun `a creative pick fills the created output, and the client takes from it`() {
        val response = handler.handle(
            StackRequest(-1, listOf(StackAction.CraftCreative(1, 1), StackAction.Transfer(64, createdOutput(stackId = -1), cursor(stackId = 0)))),
        )

        // The whole stack moved, keeping the ID it got in the created output.
        val expected = StackResponse(
            -1,
            accepted = true,
            listOf(
                ContainerChange(ContainerName.CREATED_OUTPUT, listOf(SlotChange(50, 0, 0))),
                ContainerChange(ContainerName.CURSOR, listOf(SlotChange(0, 64, 1))),
            ),
        )
        assertEquals(expected, response)
        assertEquals(SlotStack(ItemStack(stick, count = 64), 1), inventory.ui[0])
    }

    @Test
    fun `a later request can name a stack by the request that made it`() {
        pickToCursor(requestId = -1, count = 64)

        // The client hasn't heard back about request -1, so it names the cursor's stack by that.
        val response = handler.handle(StackRequest(-3, listOf(StackAction.Transfer(10, cursor(stackId = -1), hotbar(0, stackId = 0)))))

        assertTrue(response.accepted)
        assertEquals(54, inventory.ui[0]?.stack?.count)
        assertEquals(SlotStack(ItemStack(stick, count = 10), 2), inventory.items[0]) // Part of a stack starts a new one
    }

    @Test
    fun `a request naming the wrong stack is rejected, and undone`() {
        pickToCursor(requestId = -1, count = 64)

        val response = handler.handle(
            StackRequest(
                -3,
                listOf(
                    StackAction.Transfer(10, cursor(stackId = 1), hotbar(0, stackId = 0)),
                    StackAction.Transfer(5, cursor(stackId = 1), hotbar(1, stackId = 7)), // There's no stack 7 there
                ),
            ),
        )

        assertEquals(StackResponse(-3, accepted = false), response)
        assertEquals(64, inventory.ui[0]?.stack?.count)
        assertNull(inventory.items[0])
    }

    @Test
    fun `stacks of the same kind merge, up to what a slot holds`() {
        pickToCursor(requestId = -1, count = 64)
        handler.handle(StackRequest(-3, listOf(StackAction.Transfer(32, cursor(1), hotbar(0, 0)))))

        assertTrue(handler.handle(StackRequest(-5, listOf(StackAction.Transfer(32, cursor(1), hotbar(0, 2))))).accepted)
        assertEquals(SlotStack(ItemStack(stick, count = 64), 2), inventory.items[0])

        handler.handle(StackRequest(-7, listOf(StackAction.CraftCreative(2, 1), StackAction.Transfer(1, createdOutput(-7), cursor(0)))))
        assertEquals(3, inventory.ui[0]?.id) // A whole stack keeps its ID as it moves
        assertFalse(handler.handle(StackRequest(-9, listOf(StackAction.Transfer(1, cursor(3), hotbar(0, 2))))).accepted) // A sword on sticks
    }

    @Test
    fun `swords don't stack`() {
        assertTrue(handler.handle(StackRequest(-1, listOf(StackAction.CraftCreative(2, 1), StackAction.Transfer(1, createdOutput(-1), cursor(0))))).accepted)

        // A second sword onto the first would make two in one slot.
        val response = handler.handle(StackRequest(-3, listOf(StackAction.CraftCreative(2, 1), StackAction.Transfer(1, createdOutput(-3), cursor(1)))))

        assertFalse(response.accepted)
        assertEquals(SlotStack(ItemStack(sword), 1), inventory.ui[0])
    }

    @Test
    fun `swaps exchange two slots, stacks and IDs`() {
        pickToCursor(requestId = -1, count = 64)
        handler.handle(StackRequest(-3, listOf(StackAction.Transfer(64, cursor(1), hotbar(0, 0)))))

        assertTrue(handler.handle(StackRequest(-5, listOf(StackAction.Swap(hotbar(0, 1), hotbar(8, 0))))).accepted)
        assertNull(inventory.items[0])
        assertEquals(SlotStack(ItemStack(stick, count = 64), 1), inventory.items[8])
    }

    @Test
    fun `the hotbar and the inventory are names for the same slots`() {
        pickToCursor(requestId = -1, count = 64)
        handler.handle(StackRequest(-3, listOf(StackAction.Transfer(64, cursor(1), SlotRef(ContainerName.INVENTORY, 3, 0)))))

        assertTrue(handler.handle(StackRequest(-5, listOf(StackAction.Transfer(1, hotbar(3, 1), SlotRef(ContainerName.OFFHAND, 1, 0))))).accepted)
        assertEquals(63, inventory.items[3]?.stack?.count)
        assertEquals(1, inventory.offhand[0]?.stack?.count)
    }

    @Test
    fun `creative players can destroy items`() {
        pickToCursor(requestId = -1, count = 64)

        assertTrue(handler.handle(StackRequest(-3, listOf(StackAction.Destroy(64, cursor(1))))).accepted)
        assertNull(inventory.ui[0])
    }

    @Test
    fun `outside creative, there's no picking or destroying`() {
        pickToCursor(requestId = -1, count = 64)
        creativeMode = false

        assertFalse(handler.handle(StackRequest(-3, listOf(StackAction.Destroy(1, cursor(1))))).accepted)
        assertFalse(handler.handle(StackRequest(-5, listOf(StackAction.CraftCreative(1, 1)))).accepted)
    }

    @Test
    fun `dropping waits for item entities`() {
        pickToCursor(requestId = -1, count = 64)

        assertFalse(handler.handle(StackRequest(-3, listOf(StackAction.Drop(1, cursor(1))))).accepted)
        assertEquals(64, inventory.ui[0]?.stack?.count)
    }

    private fun pickToCursor(requestId: Int, count: Int) =
        assertTrue(handler.handle(StackRequest(requestId, listOf(StackAction.CraftCreative(1, 1), StackAction.Transfer(count, createdOutput(requestId), cursor(0))))).accepted)

    private fun createdOutput(stackId: Int) = SlotRef(ContainerName.CREATED_OUTPUT, CREATED_OUTPUT_SLOT, stackId)

    private fun cursor(stackId: Int) = SlotRef(ContainerName.CURSOR, 0, stackId)

    private fun hotbar(slot: Int, stackId: Int) = SlotRef(ContainerName.HOTBAR, slot, stackId)
}
