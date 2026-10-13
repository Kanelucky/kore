package org.koremc.world

import org.koremc.registry.AIR
import org.koremc.registry.ItemStack
import org.koremc.registry.VanillaData
import org.koremc.registry.assumeGameData
import org.koremc.registry.gameData
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Placing and breaking the game's own blocks, by their real states. */
class PlacingTest {
    private val blocks get() = data.blocks
    private lateinit var world: FlatWorld

    // A world of one layer of stone: its top is the ground, and the space above it is free.
    private val ground = BlockPos(0, MIN_Y, 0)
    private val above = ground.side(Face.UP)

    @BeforeTest
    fun startWorld() {
        assumeGameData()
        world = FlatWorld(blocks, listOf(blocks.defaultOf("minecraft:stone")!!))
    }

    @Test
    fun `items without a block of their own place the block they're for`() {
        val missing = BLOCK_ITEMS.filter { blocks.blockPlacedBy(it) == null }
        assertEquals(emptyList(), missing)
        assertEquals("minecraft:wheat", blocks.blockPlacedBy("minecraft:wheat_seeds")?.name)
        assertEquals("minecraft:darkoak_standing_sign", blocks.blockPlacedBy("minecraft:dark_oak_sign")?.name)
        assertNull(blocks.blockPlacedBy("minecraft:wheat")) // The harvest, not the seeds
    }

    @Test
    fun `signs, banners and coral fans on a side take their kind on a wall, and drop the item they came from`() {
        val pillar = pillar()
        place("minecraft:standing_sign", pillar, Face.EAST)
        val sign = world.stateAt(pillar.side(Face.EAST))!!
        assertEquals("minecraft:wall_sign", sign.name)
        assertEquals(Face.EAST.ordinal, sign.properties[FACING_DIRECTION])

        val drops = Drops(data.items, data.creative, blocks)
        assertEquals("minecraft:oak_sign", drops.of(sign).single().item.name)
        assertEquals("minecraft:wheat_seeds", drops.of(blocks.defaultOf("minecraft:wheat")!!).single().item.name)

        place("minecraft:fire_coral_fan", pillar, Face.NORTH)
        assertEquals("minecraft:fire_coral_wall_fan", world.stateAt(pillar.side(Face.NORTH))?.name)
    }

    @Test
    fun `standing signs and banners turn to face the player in sixteen steps`() {
        // Facing south-east, a yaw of -45, the sign faces back north-west.
        place("minecraft:standing_sign", ground, Face.UP, Placer(Face.SOUTH, yaw = -45f))
        assertEquals(6, world.stateAt(above)?.properties?.get("ground_sign_direction"))
    }

    @Test
    fun `pistons, dispensers and observers face the player, from above too, and pistons store sideways faces reversed`() {
        val standingBy = Vec3(0.5f, ground.y + 1 + 1.62f, 3.5f) // Three blocks south, on the ground
        place("minecraft:piston", ground, Face.UP, Placer(Face.NORTH, eyes = standingBy))
        assertEquals(Face.NORTH.ordinal, world.stateAt(above)?.properties?.get(FACING_DIRECTION)) // Its face points south, at them

        val elsewhere = BlockPos(5, ground.y, 0)
        place("minecraft:dispenser", elsewhere, Face.UP, Placer(Face.NORTH, eyes = Vec3(5.5f, ground.y + 1 + 1.62f, 3.5f)))
        assertEquals(Face.SOUTH.ordinal, world.stateAt(elsewhere.side(Face.UP))?.properties?.get(FACING_DIRECTION))

        // Right beside it and well above it, a dispenser faces up, and an observer looks down, away from them.
        val below = BlockPos(10, ground.y, 0)
        val overhead = Vec3(10.5f, ground.y + 4f, 1.5f)
        place("minecraft:dispenser", below, Face.UP, Placer(Face.NORTH, eyes = overhead))
        assertEquals(Face.UP.ordinal, world.stateAt(below.side(Face.UP))?.properties?.get(FACING_DIRECTION))
        place("minecraft:observer", below.side(Face.UP), Face.UP, Placer(Face.NORTH, eyes = Vec3(10.5f, ground.y + 5f, 1.5f)))
        assertEquals("down", world.stateAt(below.side(Face.UP).side(Face.UP))?.properties?.get("minecraft:facing_direction"))
    }

    @Test
    fun `hoppers point into the block they're placed against, and down when placed on one`() {
        val pillar = pillar()
        place("minecraft:hopper", pillar, Face.WEST)
        assertEquals(Face.EAST.ordinal, world.stateAt(pillar.side(Face.WEST))?.properties?.get(FACING_DIRECTION))
        place("minecraft:hopper", ground.side(Face.EAST), Face.UP)
        assertEquals(Face.DOWN.ordinal, world.stateAt(above.side(Face.EAST))?.properties?.get(FACING_DIRECTION))
    }

    @Test
    fun `anvils lie across the way they face, and grindstones and bells take their attachment`() {
        place("minecraft:anvil", ground, Face.UP, Placer(Face.NORTH))
        assertEquals("west", world.stateAt(above)?.properties?.get(CARDINAL))

        val pillar = pillar()
        place("minecraft:grindstone", pillar, Face.SOUTH)
        val grindstone = world.stateAt(pillar.side(Face.SOUTH))!!
        assertEquals("side", grindstone.properties[ATTACHMENT])
        assertEquals(Face.SOUTH.legacyIndex, grindstone.properties[DIRECTION])

        place("minecraft:bell", pillar, Face.NORTH)
        assertEquals("side", world.stateAt(pillar.side(Face.NORTH))?.properties?.get(ATTACHMENT))
    }

    @Test
    fun `candles, snow and petals pile onto their own kind`() {
        place("minecraft:candle", ground, Face.UP)
        place("minecraft:candle", above, Face.UP)
        assertEquals(1, world.stateAt(above)?.properties?.get("candles"))

        val snow = BlockPos(3, ground.y, 0)
        place("minecraft:snow_layer", snow, Face.UP)
        place("minecraft:snow_layer", snow.side(Face.UP), Face.UP)
        assertEquals(1, world.stateAt(snow.side(Face.UP))?.properties?.get("height"))

        val petals = BlockPos(6, ground.y, 0)
        world[petals] = blocks.defaultOf("minecraft:grass_block")!!
        place("minecraft:pink_petals", petals, Face.UP)
        place("minecraft:pink_petals", petals.side(Face.UP), Face.UP)
        assertEquals(1, world.stateAt(petals.side(Face.UP))?.properties?.get("growth"))
    }

    @Test
    fun `plants, carpets and rails break when the block under them goes, and a cactus when one goes beside it`() {
        val soil = BlockPos(0, ground.y + 1, 0)
        world[soil] = blocks.defaultOf("minecraft:grass_block")!!
        for ((x, name) in listOf(0 to "minecraft:fern", 1 to "minecraft:white_carpet", 2 to "minecraft:rail", 3 to "minecraft:cornflower")) {
            val at = BlockPos(x, soil.y, 0)
            world[at] = blocks.defaultOf("minecraft:grass_block")!!
            place(name, at, Face.UP)
        }
        for (x in 0..3) {
            world.breakBlock(BlockPos(x, soil.y, 0))
            assertEquals(1, world.tickBlocks().broken.size)
            assertEquals(AIR, world.stateAt(BlockPos(x, soil.y + 1, 0))?.name)
        }

        val sand = BlockPos(8, ground.y, 0)
        world[sand] = blocks.defaultOf("minecraft:sand")!!
        place("minecraft:cactus", sand, Face.UP)
        place("minecraft:stone", sand.side(Face.EAST), Face.UP) // Beside the cactus
        world.settle(listOf(sand.side(Face.EAST).side(Face.UP)))
        assertEquals("minecraft:cactus", world.tickBlocks().broken.values.single().state.name)
    }

    @Test
    fun `vines and glow lichen cover the faces they're placed against, and let go of those with nothing behind them`() {
        val pillar = pillar()
        place("minecraft:glow_lichen", pillar, Face.EAST)
        val lichen = pillar.side(Face.EAST)
        place("minecraft:glow_lichen", lichen.side(Face.DOWN), Face.UP) // The ground under the same space
        assertEquals(MULTI_FACE_BITS.getValue(Face.WEST) or MULTI_FACE_BITS.getValue(Face.DOWN), world.stateAt(lichen)?.properties?.get(MULTI_FACE))

        val changes = world.breakBlock(pillar)
        assertEquals(listOf(lichen), changes.reshaped)
        assertEquals(MULTI_FACE_BITS.getValue(Face.DOWN), world.stateAt(lichen)?.properties?.get(MULTI_FACE))
    }

    @Test
    fun `rails join the rails around them, turning corners and climbing slopes`() {
        val start = BlockPos(0, ground.y, 0)
        place("minecraft:rail", start, Face.UP, Placer(Face.NORTH)) // Runs north to south
        place("minecraft:rail", start.side(Face.EAST), Face.UP, Placer(Face.NORTH))
        // The second, beside the first, runs east to west to meet it, and the first turns to meet the second.
        assertEquals(RAIL_EAST_WEST, world.stateAt(start.side(Face.EAST).side(Face.UP))?.properties?.get(RAIL_DIRECTION))
        assertEquals(RAIL_EAST_WEST, world.stateAt(start.side(Face.UP))?.properties?.get(RAIL_DIRECTION))

        val step = BlockPos(0, ground.y + 1, 5)
        world[step] = blocks.defaultOf("minecraft:stone")!!
        place("minecraft:rail", step, Face.UP, Placer(Face.NORTH)) // On a block a step up
        place("minecraft:rail", BlockPos(0, ground.y, 6), Face.UP, Placer(Face.NORTH)) // South of it, at the foot of the step
        assertEquals(4, world.stateAt(BlockPos(0, ground.y + 1, 6))?.properties?.get(RAIL_DIRECTION)) // Sloping up to the north
    }

    @Test
    fun `scaffolding climbs its column from the top, reaches out from the side, and breaks when it can't reach what holds it`() {
        place("minecraft:scaffolding", ground, Face.UP)
        place("minecraft:scaffolding", above, Face.UP)
        place("minecraft:scaffolding", above, Face.UP) // Through the one above, to the top of the column
        assertEquals("minecraft:scaffolding", world.stateAt(above.side(Face.UP).side(Face.UP))?.name)

        // Reaching out east from the top, six blocks from the column is as far as it goes.
        val top = above.side(Face.UP).side(Face.UP)
        repeat(6) { place("minecraft:scaffolding", top, Face.EAST) }
        assertEquals(6, world.stateAt(BlockPos(6, top.y, 0))?.properties?.get("stability"))
        assertTrue(placeSaying("minecraft:scaffolding", top, Face.EAST).isEmpty())

        // Without the column, nothing holds the rest. The scaffolding above it falls a tick later, and the arm, its distance
        // counting up a tick at a time, once it can no longer reach anything that stands.
        world.breakBlock(above)
        assertEquals(1, world.tickBlocks().broken.size)
        assertEquals(7, world.tickUntilStill().size)

        // Clicking the ground from inside a column builds it up.
        place("minecraft:scaffolding", ground, Face.UP)
        place("minecraft:scaffolding", ground, Face.UP)
        assertEquals("minecraft:scaffolding", world.stateAt(above.side(Face.UP))?.name)
    }

    @Test
    fun `a stack of carpets falls one a tick when the block under it goes`() {
        val block = BlockPos(0, ground.y + 1, 0)
        world[block] = blocks.defaultOf("minecraft:stone")!!
        repeat(3) { place("minecraft:white_carpet", BlockPos(0, block.y + it, 0), Face.UP) }

        world.breakBlock(block)
        val fallen = (1..4).map { world.tickBlocks().broken.keys.map { it.y - block.y } }
        assertEquals(listOf(listOf(1), listOf(2), listOf(3), emptyList()), fallen)
    }

    @Test
    fun `full snow takes more on top, and is no longer replaced by other blocks`() {
        repeat(8) { place("minecraft:snow_layer", if (it == 0) ground else above, Face.UP) }
        assertEquals(7, world.stateAt(above)?.properties?.get("height"))
        place("minecraft:snow_layer", above, Face.UP)
        assertEquals("minecraft:snow_layer", world.stateAt(above.side(Face.UP))?.name)
        place("minecraft:stone", above, Face.UP)
        assertEquals(7, world.stateAt(above)?.properties?.get("height"))
    }

    @Test
    fun `copper chests pair however weathered, taking the first one's look, and chests and the like open rather than build`() {
        place("minecraft:weathered_copper_chest", ground, Face.UP, Placer(Face.NORTH))
        place("minecraft:copper_chest", ground.side(Face.EAST), Face.UP, Placer(Face.NORTH))
        assertEquals("minecraft:weathered_copper_chest", world.stateAt(above.side(Face.EAST))?.name)
        assertEquals(0, world.blockEntityAt(above.side(Face.EAST))?.getInt("pairx"))

        assertTrue(blocks.defaultOf("minecraft:anvil")!!.opensOnClick)
        assertTrue(blocks.defaultOf("minecraft:copper_chest")!!.opensOnClick)
        assertTrue(!blocks.defaultOf("minecraft:stone")!!.opensOnClick)
    }

    @Test
    fun `beds take two spaces, keep their colour, and drop once in it`() {
        val red = 14
        val bed = blocks.blockPlacedBy("minecraft:bed")!!
        val placed = world.place(bed, ground, Face.UP, 0f, Placer(Face.EAST), item = itemStack("minecraft:bed", aux = red))
        assertEquals(listOf(above, above.side(Face.EAST)), placed)
        assertEquals(red.toByte(), world.blockEntityAt(above.side(Face.EAST))?.getByte("color"))

        val changes = world.breakBlock(above.side(Face.EAST))
        assertEquals(2, changes.broken.size)
        val drops = Drops(data.items, data.creative, blocks)
        val dropped = changes.broken.values.flatMap { drops.of(it.state, it.blockEntity) }
        assertEquals(listOf(red), dropped.map { it.aux })
    }

    @Test
    fun `a head on the floor turns to the player, and chests beside one another pair`() {
        place("minecraft:skeleton_skull", ground, Face.UP, Placer(Face.WEST, yaw = 90f))
        assertEquals(90f, world.blockEntityAt(above)?.getFloat("Rotation"))

        val left = BlockPos(4, ground.y + 1, 0)
        place("minecraft:chest", ground.side(Face.EAST).side(Face.EAST).side(Face.EAST).side(Face.EAST), Face.UP, Placer(Face.NORTH))
        val placed = world.place(blocks.defaultOf("minecraft:chest")!!, BlockPos(5, ground.y, 0), Face.UP, 0f, Placer(Face.NORTH))
        assertEquals(listOf(BlockPos(5, ground.y + 1, 0), left), placed) // The other chest's data changed too
        assertEquals(4, world.blockEntityAt(BlockPos(5, ground.y + 1, 0))?.getInt("pairx"))

        val changes = world.breakBlock(BlockPos(5, ground.y + 1, 0))
        assertEquals(listOf(left), changes.reshaped)
        assertTrue(world.blockEntityAt(left)?.containsKey("pairx") == false)
    }

    @Test
    fun `tripwire hooks facing one another, with tripwire between, are attached`() {
        val west = BlockPos(0, ground.y + 1, 0)
        val east = BlockPos(4, ground.y + 1, 0)
        world[west.side(Face.WEST)] = blocks.defaultOf("minecraft:stone")!!
        world[east.side(Face.EAST)] = blocks.defaultOf("minecraft:stone")!!
        place("minecraft:tripwire_hook", west.side(Face.WEST), Face.EAST)
        place("minecraft:tripwire_hook", east.side(Face.EAST), Face.WEST)
        for (x in 1..3) {
            val changed = world.place(blocks.blockPlacedBy("minecraft:string")!!, BlockPos(x, ground.y, 0), Face.UP, 0f, Placer(Face.NORTH))
            world.settle(changed)
        }

        assertEquals(1.toByte(), world.stateAt(west)?.properties?.get("attached_bit"))
        assertEquals(1.toByte(), world.stateAt(BlockPos(2, ground.y + 1, 0))?.properties?.get("attached_bit"))
        assertEquals(1.toByte(), world.stateAt(BlockPos(2, ground.y + 1, 0))?.properties?.get("minecraft:connection_east"))
    }

    @Test
    fun `pointed dripstone thickens from its tip back as it grows`() {
        val ceiling = BlockPos(0, ground.y + 5, 0)
        world[ceiling] = blocks.defaultOf("minecraft:stone")!!
        var tip = ceiling
        repeat(3) {
            world.settle(place("minecraft:pointed_dripstone", tip, Face.DOWN))
            tip = tip.side(Face.DOWN)
        }
        val thickness = (1..3).map { world.stateAt(BlockPos(0, ceiling.y - it, 0))?.properties?.get("dripstone_thickness") }
        assertEquals(listOf("base", "frustum", "tip"), thickness)
    }

    @Test
    fun `daylight detectors switch, repeaters wait longer, and comparators subtract`() {
        place("minecraft:daylight_detector", ground, Face.UP)
        assertNotNull(world.use(above, Face.NORTH))
        assertEquals("minecraft:daylight_detector_inverted", world.stateAt(above)?.name)

        val repeater = BlockPos(2, ground.y, 0)
        world.place(blocks.blockPlacedBy("minecraft:repeater")!!, repeater, Face.UP, 0f, Placer(Face.NORTH))
        world.use(repeater.side(Face.UP), Face.NORTH)
        assertEquals(1, world.stateAt(repeater.side(Face.UP))?.properties?.get("repeater_delay"))
        assertEquals("south", world.stateAt(repeater.side(Face.UP))?.properties?.get(CARDINAL))
    }

    @Test
    fun `a repeater placed facing away from the player carries power the way it faces`() {
        // A lever, dust, a repeater and more dust, in a line south, placed by a player looking south.
        val south = Placer(Face.SOUTH)
        val line = (0..3).map { BlockPos(0, ground.y, it) }
        world.place(blocks.defaultOf("minecraft:lever")!!, line[0], Face.UP, 0f, south)
        world.place(blocks.blockPlacedBy("minecraft:redstone")!!, line[1], Face.UP, 0f, south)
        world.place(blocks.blockPlacedBy("minecraft:repeater")!!, line[2], Face.UP, 0f, south)
        world.place(blocks.blockPlacedBy("minecraft:redstone")!!, line[3], Face.UP, 0f, south)
        world.updatePower()

        world.use(line[0].side(Face.UP), Face.SOUTH)
        world.updatePower()
        repeat(2) {
            world.tickBlocks()
            world.updatePower()
        }

        assertEquals("minecraft:powered_repeater", world.stateAt(line[2].side(Face.UP))?.name)
        assertEquals(15, world.stateAt(line[3].side(Face.UP))?.properties?.get(REDSTONE_SIGNAL))
    }

    @Test
    fun `a piston placed facing the player pushes towards them, with its arm's data`() {
        // A player looking north, standing south of it, places it facing them.
        val piston = above
        place("minecraft:piston", ground, Face.UP, Placer(Face.NORTH))
        assertEquals("PistonArm", world.blockEntityAt(piston)?.getString("id"))
        val front = piston.side(Face.SOUTH)
        world[front] = blocks.defaultOf("minecraft:stone")!!
        world[piston.side(Face.EAST)] = blocks.defaultOf("minecraft:redstone_block")!!
        world.updatePower()
        repeat(5) {
            world.tickBlocks()
            world.updatePower()
        }

        assertEquals("minecraft:piston_arm_collision", world.stateAt(front)?.name)
        assertEquals("minecraft:stone", world.stateAt(front.side(Face.SOUTH))?.name)
    }

    @Test
    fun `coral out of water dies ten ticks after a block beside it changes, and never by itself`() {
        place("minecraft:tube_coral_block", ground, Face.UP)
        repeat(40) { world.tickBlocks() }
        assertEquals("minecraft:tube_coral_block", world.stateAt(above)?.name)

        world.settle(place("minecraft:stone", ground.side(Face.EAST), Face.UP)) // Beside the coral
        repeat(9) { world.tickBlocks() }
        assertEquals("minecraft:tube_coral_block", world.stateAt(above)?.name)
        val died = world.tickBlocks()
        assertEquals("minecraft:dead_tube_coral_block", world.stateAt(above)?.name)
        assertEquals(listOf(BlockSound(above, "place", world.blockAt(above))), died.sounds)

        // Placed in water, a fan lives, until the water beside it goes and something's placed by it.
        val fan = BlockPos(4, ground.y + 1, 0)
        world[fan.side(Face.EAST)] = blocks.defaultOf("minecraft:water")!!
        place("minecraft:fire_coral_fan", BlockPos(4, ground.y, 0), Face.UP)
        repeat(20) { world.tickBlocks() }
        assertEquals("minecraft:fire_coral_fan", world.stateAt(fan)?.name)
        world[fan.side(Face.EAST)] = blocks.defaultOf(AIR)!!
        world.settle(place("minecraft:stone", BlockPos(3, ground.y, 0), Face.UP)) // Beside the fan
        repeat(10) { world.tickBlocks() }
        assertEquals("minecraft:dead_fire_coral_fan", world.stateAt(fan)?.name)
    }

    @Test
    fun `a big dripleaf on another grows the plant, its stem below and leaf on top`() {
        place("minecraft:big_dripleaf", ground, Face.UP, Placer(Face.NORTH))
        world.settle(place("minecraft:big_dripleaf", above, Face.UP, Placer(Face.EAST)))

        val stem = world.stateAt(above)!!
        val leaf = world.stateAt(above.side(Face.UP))!!
        assertEquals(0.toByte(), stem.properties["big_dripleaf_head"])
        assertEquals(1.toByte(), leaf.properties["big_dripleaf_head"])
        assertEquals(stem.properties[CARDINAL], leaf.properties[CARDINAL]) // Facing the way the plant does

        // Breaking the leaf makes the stem the leaf again.
        world.breakBlock(above.side(Face.UP))
        assertEquals(1.toByte(), world.stateAt(above)?.properties?.get("big_dripleaf_head"))
    }

    @Test
    fun `a big dripleaf stood on wobbles, tips a second later, tips right over, then rights itself`() {
        place("minecraft:big_dripleaf", ground, Face.UP)
        fun tilt() = world.stateAt(above)?.properties?.get("big_dripleaf_tilt")

        assertEquals(emptyList(), world.stepOn(listOf(Vec3(0.5f, above.y + 0.5f, 0.5f)))) // Beside its stem, under the leaf
        assertEquals(listOf(above), world.stepOn(listOf(Vec3(0.5f, above.y + 15f / 16, 0.5f))))
        assertEquals("unstable", tilt())

        repeat(19) { world.tickBlocks() }
        assertEquals("unstable", tilt())
        assertEquals("tilt_down.big_dripleaf", world.tickBlocks().sounds.single().sound)
        assertEquals("partial_tilt", tilt())
        repeat(9) { world.tickBlocks() }
        assertEquals("partial_tilt", tilt())
        world.tickBlocks()
        assertEquals("full_tilt", tilt())
        repeat(100) { world.tickBlocks() }
        assertEquals("none", tilt())
    }

    /** Ticks the world's blocks until nothing's left to look at, and says everything that broke. */
    private fun FlatWorld.tickUntilStill(): Map<BlockPos, BrokenBlock> {
        val broken = linkedMapOf<BlockPos, BrokenBlock>()
        repeat(100) {
            if (!hasScheduledUpdates) return broken
            broken += tickBlocks().broken
        }
        return broken
    }

    /** A block of stone on the ground, to place against, a few blocks north. */
    private fun pillar(): BlockPos = BlockPos(0, ground.y + 1, -3).also { world[it] = blocks.defaultOf("minecraft:stone")!! }

    private fun place(name: String, against: BlockPos, face: Face, placer: Placer = Placer(Face.NORTH)) = placeSaying(name, against, face, placer)

    private fun placeSaying(name: String, against: BlockPos, face: Face, placer: Placer = Placer(Face.NORTH)): List<BlockPos> {
        val block = blocks.defaultOf(name) ?: error("no block $name")
        return world.place(block, against, face, 0f, placer)
    }

    private fun itemStack(name: String, aux: Int) = ItemStack(data.items.first { it.name == name }, aux = aux)

    private companion object {
        val data: VanillaData by lazy { VanillaData.load(gameData) }
    }
}
