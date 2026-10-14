package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.registry.AIR
import org.koremc.registry.BlockState
import org.koremc.registry.ItemStack
import org.koremc.registry.ItemStacks
import org.koremc.registry.VanillaData
import org.koremc.registry.assumeGameData
import org.koremc.registry.gameData
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Using tools and other items on the game's own blocks: tilling, stripping, waxing, signs, frames, end portals and flower pots. */
class UsingItemsTest {
    private val blocks get() = data.blocks
    private lateinit var world: FlatWorld
    private lateinit var items: ItemStacks

    // A world of one layer of stone: its top is the ground, and the space above it is free.
    private val ground = BlockPos(0, MIN_Y, 0)
    private val above = ground.side(Face.UP)
    private val beside = above.side(Face.EAST)

    @BeforeTest
    fun startWorld() {
        assumeGameData()
        world = FlatWorld(blocks, listOf(blocks.defaultOf("minecraft:stone")!!))
        items = ItemStacks(data.items, data.creative, blocks)
    }

    private fun item(name: String): ItemStack = items.of(name) ?: error("no item $name")

    /** The block called [name], with [properties] in place of its defaults. */
    private fun block(name: String, vararg properties: Pair<String, Any>): BlockState {
        val default = blocks.defaultOf(name) ?: error("no block $name")
        return if (properties.isEmpty()) default else blocks.stateOf(name, default.properties + properties)
    }

    private fun put(name: String, at: BlockPos = above, vararg properties: Pair<String, Any>) = block(name, *properties).also { world[at] = it }

    private fun use(tool: String, at: BlockPos = above, face: Face = Face.UP) = world.useToolOn(at, face, item(tool), Face.NORTH, items)

    @Test
    fun `hoes till grass into farmland, and shovels flatten it into a path, only with air above`() {
        put("minecraft:grass_block")
        val tilled = use("minecraft:iron_hoe")
        assertEquals("minecraft:farmland", world.stateAt(above)?.name)
        assertEquals(HeldAfter.WORN, tilled?.held)
        assertEquals("item.use.on", tilled?.sound?.sound)

        put("minecraft:grass_block", beside)
        put("minecraft:stone", beside.side(Face.UP))
        assertNull(use("minecraft:iron_shovel", beside))
        world[beside.side(Face.UP)] = block(AIR)
        assertNull(use("minecraft:iron_shovel", beside, Face.DOWN)) // Not from below
        use("minecraft:iron_shovel", beside, Face.NORTH)
        assertEquals("minecraft:grass_path", world.stateAt(beside)?.name)
    }

    @Test
    fun `hoes turn coarse dirt into dirt, and rooted dirt too, which gives up its roots`() {
        put("minecraft:coarse_dirt")
        use("minecraft:wooden_hoe")
        assertEquals("minecraft:dirt", world.stateAt(above)?.name)

        put("minecraft:dirt_with_roots", beside)
        put("minecraft:stone", beside.side(Face.UP))
        val rooted = use("minecraft:wooden_hoe", beside, Face.NORTH)
        assertEquals("minecraft:dirt", world.stateAt(beside)?.name)
        assertEquals(listOf("minecraft:hanging_roots"), rooted?.dropped?.map { it.item.name })
    }

    @Test
    fun `axes strip logs the way they lie, once`() {
        put("minecraft:oak_log", above, "pillar_axis" to "x")
        use("minecraft:stone_axe")
        assertEquals(block("minecraft:stripped_oak_log", "pillar_axis" to "x"), world.stateAt(above))
        assertNull(use("minecraft:stone_axe"))

        put("minecraft:crimson_stem", beside)
        use("minecraft:stone_axe", beside)
        assertEquals("minecraft:stripped_crimson_stem", world.stateAt(beside)?.name)
    }

    @Test
    fun `honeycomb waxes copper, and axes take the wax off, then a stage of weathering at a time`() {
        put("minecraft:weathered_cut_copper_slab", above, "minecraft:vertical_half" to "top")

        val waxed = use("minecraft:honeycomb")
        assertEquals(block("minecraft:waxed_weathered_cut_copper_slab", "minecraft:vertical_half" to "top"), world.stateAt(above))
        assertEquals(HeldAfter.USED_UP, waxed?.held)
        assertEquals("copper.wax.on", waxed?.sound?.sound)
        assertNull(use("minecraft:honeycomb")) // Waxed already

        assertEquals("copper.wax.off", use("minecraft:iron_axe")?.sound?.sound)
        assertEquals("minecraft:weathered_cut_copper_slab", world.stateAt(above)?.name)
        assertEquals("scrape", use("minecraft:iron_axe")?.sound?.sound)
        assertEquals("minecraft:exposed_cut_copper_slab", world.stateAt(above)?.name)
        use("minecraft:iron_axe")
        assertEquals(block("minecraft:cut_copper_slab", "minecraft:vertical_half" to "top"), world.stateAt(above))
        assertNull(use("minecraft:iron_axe")) // Nothing left to scrape

        // Plain copper is a "copper_block" until it's waxed, and lightning rods weather as copper does.
        put("minecraft:copper_block", beside)
        use("minecraft:honeycomb", beside)
        assertEquals("minecraft:waxed_copper", world.stateAt(beside)?.name)
        put("minecraft:lightning_rod", beside)
        use("minecraft:honeycomb", beside)
        assertEquals("minecraft:waxed_lightning_rod", world.stateAt(beside)?.name)
    }

    @Test
    fun `shears carve a pumpkin's face where it's clicked, or towards the player from above, and a seed drops`() {
        put("minecraft:pumpkin")
        val carved = use("minecraft:shears", above, Face.EAST)
        assertEquals(block("minecraft:carved_pumpkin", CARDINAL to "east"), world.stateAt(above))
        assertEquals(HeldAfter.WORN, carved?.held)
        assertEquals(listOf("minecraft:pumpkin_seeds" to 1), carved?.dropped?.map { it.item.name to it.count })

        put("minecraft:pumpkin", beside)
        use("minecraft:shears", beside, Face.UP)
        assertEquals("south", world.stateAt(beside)?.properties?.get(CARDINAL)) // The player faces north
        assertNull(use("minecraft:shears", beside, Face.NORTH)) // Carved already
    }

    @Test
    fun `signs are dyed, lit and put out a side at a time, written on, and once waxed, nothing changes them`() {
        world.place(block("minecraft:standing_sign"), ground, Face.UP, 0f, Placer(Face.NORTH))
        assertEquals("Sign", world.blockEntityAt(above)?.getString("id"))

        val dyed = world.markSign(above, front = true, item("minecraft:red_dye"))
        assertEquals(HeldAfter.USED_UP, dyed?.held)
        assertEquals("sign.dye.use", dyed?.sound?.sound)
        assertEquals(0xFFB02E26.toInt(), side(front = true).getInt("SignTextColor"))
        assertEquals(0xFF000000.toInt(), side(front = false).getInt("SignTextColor"))
        assertNull(world.markSign(above, front = true, item("minecraft:red_dye"))) // Red already

        world.markSign(above, front = false, item("minecraft:glow_ink_sac"))
        assertTrue(side(front = false).getBoolean("IgnoreLighting"))
        world.markSign(above, front = false, item("minecraft:ink_sac"))
        assertFalse(side(front = false).getBoolean("IgnoreLighting"))

        assertTrue(world.writeSign(above, written("Hello\nthere")))
        assertEquals("Hello\nthere", side(front = true).getString("Text"))
        assertEquals(0xFFB02E26.toInt(), side(front = true).getInt("SignTextColor")) // The colour the client sent isn't taken

        world.markSign(above, front = true, item("minecraft:honeycomb"))
        assertTrue(world.signWaxed(above))
        assertFalse(world.writeSign(above, written("Changed")))
        val refused = world.markSign(above, front = true, item("minecraft:blue_dye"))
        assertEquals("block.sign.waxed_interact_fail", refused?.sound?.sound)
        assertEquals(emptyList(), refused?.changed)
        assertEquals("Hello\nthere", side(front = true).getString("Text"))
    }

    private fun side(front: Boolean): NbtMap = world.blockEntityAt(above)!!.getCompound(if (front) "FrontText" else "BackText")

    /** A sign's data as its client sends it back, with [text] on the front, in a colour of the client's own. */
    private fun written(text: String) = NbtMap.builder()
        .putCompound("FrontText", NbtMap.builder().putString("Text", text).putInt("SignTextColor", 0).build())
        .build()

    @Test
    fun `the front of a sign faces whoever put it up`() {
        val eyes = above.y + 1.6f
        val south = block("minecraft:standing_sign", "ground_sign_direction" to 0)
        assertTrue(facesFront(above, south, Vec3(0.5f, eyes, 3f)))
        assertFalse(facesFront(above, south, Vec3(0.5f, eyes, -3f)))

        val west = block("minecraft:standing_sign", "ground_sign_direction" to 4)
        assertTrue(facesFront(above, west, Vec3(-3f, eyes, 0.5f)))

        val onWall = block("minecraft:wall_sign", FACING_DIRECTION to Face.EAST.ordinal)
        assertTrue(facesFront(above, onWall, Vec3(3f, eyes, 0.5f)))
        assertFalse(facesFront(above, onWall, Vec3(-3f, eyes, 0.5f)))
    }

    @Test
    fun `one of an item goes in a frame, turns an eighth at a click, and comes out when the frame's hit`() {
        val frame = put("minecraft:frame")
        world.setBlockEntity(above, blockEntityFor(frame, above, Placer(Face.NORTH), null))

        val framed = world.useFrame(above, frame, item("minecraft:diamond").copy(count = 5))
        assertEquals(HeldAfter.USED_UP, framed?.held)
        assertEquals("block.itemframe.add_item", framed?.sound?.sound)
        assertEquals("minecraft:diamond" to 1, world.frameItem(above, items)?.let { it.item.name to it.count })
        assertEquals(0, world.frameTurns(above))

        repeat(3) { world.useFrame(above, frame, item("minecraft:stone")) }
        assertEquals(3, world.frameTurns(above))
        assertEquals("minecraft:diamond", world.frameItem(above, items)?.item?.name) // Turned, not swapped
        repeat(5) { world.useFrame(above, frame, null) }
        assertEquals(0, world.frameTurns(above)) // All the way round

        assertEquals("minecraft:diamond", world.takeFromFrame(above, items)?.item?.name)
        assertNull(world.frameItem(above, items))
        assertNull(world.takeFromFrame(above, items))
        assertNull(world.useFrame(above, frame, null)) // Empty, and clicked with nothing
    }

    @Test
    fun `a map fills its frame until it's knocked out`() {
        val frame = put("minecraft:glow_frame")
        world.useFrame(above, frame, item(FILLED_MAP))
        assertEquals(1.toByte(), world.stateAt(above)?.properties?.get("item_frame_map_bit"))
        assertEquals("GlowItemFrame", world.blockEntityAt(above)?.getString("id"))

        world.takeFromFrame(above, items)
        assertEquals(0.toByte(), world.stateAt(above)?.properties?.get("item_frame_map_bit"))
    }

    /** The twelve spots of an end portal's ring of frames around [middle], each with the side of the ring it's on. */
    private fun ringAround(middle: BlockPos): List<Pair<BlockPos, Face>> = AROUND.flatMap { side ->
        val across = AROUND.first { it.dx == side.dz && it.dz == side.dx }
        (-1..1).map { step -> BlockPos(middle.x + side.dx * 2 + across.dx * step, middle.y, middle.z + side.dz * 2 + across.dz * step) to side }
    }

    private fun portalFrame(facing: Face) = block("minecraft:end_portal_frame", CARDINAL to facing.id, "end_portal_eye_bit" to bit(false))

    private fun fill(at: BlockPos) = world.fillPortalFrame(at, world.stateAt(at)!!)

    @Test
    fun `twelve frames facing in, each with an eye, open an end portal in the middle`() {
        val middle = BlockPos(10, above.y, 10)
        val ring = ringAround(middle)
        for ((at, side) in ring) world[at] = portalFrame(side.opposite)

        for ((at, _) in ring.dropLast(1)) {
            val filled = fill(at)
            assertEquals("block.end_portal_frame.fill", filled?.sound?.sound)
            assertEquals(HeldAfter.USED_UP, filled?.held)
        }
        assertNull(fill(ring.first().first)) // An eye goes in once, and never comes out
        assertEquals(AIR, world.stateAt(middle)?.name)

        val opened = fill(ring.last().first)
        assertEquals(BlockSound(middle, "block.end_portal.spawn", -1), opened?.sound)
        assertEquals(10, opened?.changed?.size) // The last frame, and the nine blocks of the portal
        for (dx in -1..1) for (dz in -1..1) {
            assertEquals("minecraft:end_portal", world.stateAt(BlockPos(middle.x + dx, middle.y, middle.z + dz))?.name)
        }
    }

    @Test
    fun `breaking a frame closes the portal, all of it`() {
        val middle = BlockPos(10, above.y, 10)
        val ring = ringAround(middle)
        for ((at, side) in ring) world[at] = portalFrame(side.opposite)
        for ((at, _) in ring) fill(at)

        val changes = world.breakBlock(ring[4].first)
        val portal = (-1..1).flatMap { dx -> (-1..1).map { dz -> BlockPos(middle.x + dx, middle.y, middle.z + dz) } }
        assertEquals(portal.toSet(), changes.reshaped.toSet())
        assertTrue(portal.all { world.stateAt(it)?.name == AIR })
        assertEquals("minecraft:end_portal_frame", world.stateAt(ring[5].first)?.name) // The other frames stay
    }

    @Test
    fun `a ring with a frame facing out opens nothing`() {
        val middle = BlockPos(10, above.y, 10)
        val ring = ringAround(middle)
        for ((at, side) in ring) world[at] = portalFrame(if (at == ring[4].first) side else side.opposite)

        for ((at, _) in ring) fill(at)
        assertEquals(AIR, world.stateAt(middle)?.name)
    }

    @Test
    fun `a plant goes in an empty flower pot, and comes out into an empty hand`() {
        val pot = put("minecraft:flower_pot")
        world.setBlockEntity(above, blockEntityFor(pot, above, Placer(Face.NORTH), null))
        val poppy = block("minecraft:poppy")

        assertNull(world.usePot(above, pot, item("minecraft:stone"), block("minecraft:stone"), items)) // Not a plant for pots
        val planted = world.usePot(above, pot, item("minecraft:poppy"), poppy, items)
        assertEquals(HeldAfter.USED_UP, planted?.held)
        assertEquals(poppy, world.plantInPot(above))
        assertEquals(1.toByte(), world.stateAt(above)?.properties?.get("update_bit"))

        val full = world.stateAt(above)!!
        val refused = world.usePot(above, full, item("minecraft:dandelion"), block("minecraft:dandelion"), items) // One to a pot
        assertEquals(emptyList(), refused?.changed) // Taking the click, so the dandelion isn't placed beside it
        assertEquals(poppy, world.plantInPot(above))
        val taken = world.usePot(above, full, null, null, items)
        assertEquals("minecraft:poppy", taken?.given?.item?.name)
        assertNull(world.plantInPot(above))
        assertEquals(0.toByte(), world.stateAt(above)?.properties?.get("update_bit"))

        world.usePot(above, world.stateAt(above)!!, item("minecraft:oak_sapling"), block("minecraft:oak_sapling"), items)
        assertEquals("minecraft:oak_sapling", world.plantInPot(above)?.name)
    }

    private companion object {
        val data: VanillaData by lazy { VanillaData.load(gameData) }
    }
}
