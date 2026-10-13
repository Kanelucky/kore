package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.inventory.Container
import org.koremc.registry.AIR
import org.koremc.registry.AIR_NETWORK_ID
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
import org.koremc.registry.BlockTraits
import org.koremc.registry.Fuel
import org.koremc.registry.ItemStack
import org.koremc.registry.Recipes
import java.util.TreeMap

/**
 * Chunks name biomes by number, and Cloudburst's data has no numbers for
 * vanilla biomes. So plains is hard-coded, as 1: the ID other servers send
 * it with.
 */
const val PLAINS_BIOME_ID = 1

/**
 * A world that starts out the same in every column: [layers] of blocks
 * from the bottom of the world up, air above them, and one biome
 * throughout. Until a player changes a block in a chunk, the chunk is that
 * same column, so the world holds just one of those, plus each chunk that
 * players have changed, or that [loader] had saved, which it asks for the
 * first time anything looks at the chunk. The items lying in it stay until
 * they're picked up or go.
 *
 * Like the sessions that change it, it runs on the UDP event loop.
 */
class FlatWorld(
    /** Every block state the world can hold, by network ID. */
    val blocks: BlockPalette,
    layers: List<BlockState>,
    biome: Int = PLAINS_BIOME_ID,
    private val loader: ChunkLoader = ChunkLoader.NONE,
    /** Where players appear: by default standing on the top layer, in the corner of chunk 0, 0. */
    val spawn: BlockPos = BlockPos(0, MIN_Y + layers.size, 0),
    /** What the game says of each block state, like what liquid does to it. */
    val traits: BlockTraits = BlockTraits.NONE,
) {

    private val generated: Chunk

    /** The chunks that don't match [generated]: those players changed, and those [loader] had saved. */
    private val changed = HashMap<ChunkPos, Chunk>()

    /** The chunks [loader] has been asked for, each only ever once. */
    private val asked = HashSet<ChunkPos>()

    /** The chunks whose blocks changed since the world was last saved. */
    internal val unsaved = HashSet<ChunkPos>()

    /** Every chunk that doesn't match [generated], by where it is. */
    internal val chunks: Map<ChunkPos, Chunk> get() = changed

    /** How many times the world's blocks have ticked, which updates are scheduled by. */
    internal var blockTicks = 0L

    /** Blocks to look at again, by the tick to, and what for. */
    internal val scheduled = TreeMap<Long, LinkedHashSet<Pair<BlockPos, BlockUpdate>>>()

    /** Whether any block is waiting to be looked at again. */
    val hasScheduledUpdates get() = scheduled.isNotEmpty()

    /** The block entities in each chunk that has any, by where they are. */
    private val blockEntities = HashMap<ChunkPos, HashMap<BlockPos, NbtMap>>()

    private val itemEntities = LinkedHashMap<Long, ItemEntity>()
    private val orbEntities = LinkedHashMap<Long, ExperienceOrb>()
    private val fallingEntities = LinkedHashMap<Long, FallingBlock>()
    private var nextEntityId = 1L

    /** The items lying in the world, oldest first. */
    val items: Collection<ItemEntity> get() = itemEntities.values

    /** The experience orbs in the world, oldest first. */
    val orbs: Collection<ExperienceOrb> get() = orbEntities.values

    /** The blocks falling, oldest first. */
    val fallingBlocks: Collection<FallingBlock> get() = fallingEntities.values

    /**
     * Where blocks, or the water inside them, changed since the blocks
     * around them were last looked at: one above may have nothing under it
     * now and fall, and liquid beside one may flow.
     */
    internal val blockChanges = LinkedHashSet<BlockPos>()

    /** The liquid due to move, so that none is due twice at once. */
    internal val flowDue = HashSet<BlockPos>()

    /** Where the water inside blocks changed lately, which players are shown as well as the blocks. */
    internal val liquidChanges = LinkedHashSet<BlockPos>()

    init {
        require(layers.size < SUB_CHUNK_COUNT * 16) { "${layers.size} layers leave no room to stand in the overworld" }

        // Every sub-chunk the layers reach. A block's y within its sub-chunk is the low four bits of its index.
        val subChunks = (0 until (layers.size + 15) / 16).map { subChunk ->
            PalettedStorage.of(IntArray(SUB_CHUNK_VOLUME) { block -> layers.getOrNull(subChunk * 16 + (block and 0xF))?.networkId ?: AIR_NETWORK_ID })
        }
        // The same storage all the way up, which goes out as one storage and then a byte for each repeat.
        val biomes = PalettedStorage.filled(biome)
        generated = Chunk(subChunks, List(SUB_CHUNK_COUNT) { biomes })
    }

    fun chunkAt(pos: ChunkPos): Chunk = changed[pos] ?: load(pos)

    /**
     * The chunk at [pos] as [loader] had it, the first time it's asked for,
     * with what it keeps besides its blocks put back once it's in, or the
     * generated chunk if the loader has none.
     */
    private fun load(pos: ChunkPos): Chunk {
        if (!asked.add(pos)) return generated
        val loaded = loader.load(pos) ?: return generated
        loaded.chunk?.let { changed[pos] = it }
        loaded.restore(this)
        return changed[pos] ?: generated
    }

    /** The network ID of the block at [pos]. */
    fun blockAt(pos: BlockPos): Int = chunkAt(pos.chunk).blockAt(pos)

    /** The block at [pos], or null if it's one [blocks] doesn't have. */
    fun stateAt(pos: BlockPos): BlockState? = blocks[blockAt(pos)]

    /** An ID for a new entity, unique among players and items alike. */
    fun newEntityId() = nextEntityId++

    private var nextStackId = 1

    /** An ID for a new stack of items, unique among every player's and every container's, which clients name stacks by. */
    fun newStackId() = nextStackId++

    /** Puts [stack] in the world at [position], moving at [velocity], for anyone to pick up after [pickupDelay] ticks. */
    fun spawnItem(stack: ItemStack, position: Vec3, velocity: Vec3, pickupDelay: Int): ItemEntity =
        ItemEntity(newEntityId(), stack, position, velocity, pickupDelay).also { itemEntities[it.id] = it }

    /** The item [id], or null if it's gone. */
    fun itemWith(id: Long): ItemEntity? = itemEntities[id]

    fun removeItem(id: Long) {
        itemEntities.remove(id)
    }

    /** Puts an orb worth [value] experience in the world at [position], moving at [velocity]. */
    fun spawnOrb(value: Int, position: Vec3, velocity: Vec3): ExperienceOrb =
        ExperienceOrb(newEntityId(), value, position, velocity).also { orbEntities[it.id] = it }

    /** The orb [id], or null if it's gone. */
    fun orbWith(id: Long): ExperienceOrb? = orbEntities[id]

    fun removeOrb(id: Long) {
        orbEntities.remove(id)
    }

    /** Sets [state] falling from [position], having started at the height [fromY], moving at [velocity]. */
    fun spawnFallingBlock(state: BlockState, position: Vec3, fromY: Int, velocity: Vec3 = Vec3(0f, 0f, 0f)): FallingBlock =
        FallingBlock(newEntityId(), state, position, fromY, velocity).also { fallingEntities[it.id] = it }

    fun removeFallingBlock(id: Long) {
        fallingEntities.remove(id)
    }

    /** What's inside the block at [pos], in the second layer: the water in a fence, say, or null for nothing. */
    fun liquidAt(pos: BlockPos): BlockState? = chunkAt(pos.chunk).liquidAt(pos).takeIf { it != AIR_NETWORK_ID }?.let { blocks[it] }

    /** Puts [liquid] inside the block at [pos], or takes what's there out, for null. */
    fun setLiquid(pos: BlockPos, liquid: BlockState?) {
        require(pos.y in MIN_Y..MAX_Y) { "$pos is outside the overworld" }
        changed[pos.chunk] = chunkAt(pos.chunk).withLiquid(pos, liquid?.networkId ?: AIR_NETWORK_ID)
        unsaved += pos.chunk
        blockChanges += pos
        liquidChanges += pos
    }

    /**
     * Puts [state] at [pos], which must be within the overworld's height.
     * Putting a block of another kind there forgets the block entity the
     * old one had. Water inside the old block stays where it is: in the
     * new one, if it can hold water, and as water by itself if the new one
     * is air. A block that can hold water put in a water source takes the
     * water in.
     */
    operator fun set(pos: BlockPos, state: BlockState) {
        require(pos.y in MIN_Y..MAX_Y) { "$pos is outside the overworld" }
        val inside = liquidAt(pos)
        if (inside != null && state.name == AIR) {
            setLiquid(pos, null)
            return set(pos, liquidState(inside.liquid ?: Liquid.WATER, inside.liquidDepth))
        }
        val replaced = stateAt(pos)
        if (inside != null && !holdsWater(state)) setLiquid(pos, null)
        if (inside == null && replaced?.liquid == Liquid.WATER && replaced.liquidDepth == 0 && holdsWater(state)) {
            setLiquid(pos, liquidState(Liquid.WATER, 0, inside = true))
        }
        // A furnace lighting up, or going out, is the same furnace.
        if (replaced?.let(::kindOf) != kindOf(state)) {
            blockEntities[pos.chunk]?.remove(pos)
            containers.remove(pos)
            machines.remove(pos)
            poweredOpen.remove(pos)
            torchToggles.remove(pos)
            redstoneDue.remove(pos)
            comparatorPower.remove(pos)
            pistonArms.remove(pos)
            beacons.remove(pos)
        }
        changed[pos.chunk] = chunkAt(pos.chunk).with(pos, state.networkId)
        unsaved += pos.chunk
        // A hopper works from the moment it's there, whether anyone opens it or not.
        if (state.name == HOPPER) machines.getOrPut(pos) { Hopper() }
        if (state.isComparator) comparators += pos else comparators -= pos
        if (state.name == BEACON) beacons.putIfAbsent(pos, 0)
        powerChanges += pos
        blockChanges += pos
    }

    /** Where blocks changed since redstone last looked, which may have powered something, or stopped powering it. */
    internal val powerChanges = LinkedHashSet<BlockPos>()

    /**
     * The doors, trapdoors and fence gates that were powered when redstone
     * last looked, a door by its lower half, so that only power reaching
     * them or going opens or shuts them.
     */
    internal val poweredOpen = HashSet<BlockPos>()

    /** When each redstone torch went out lately, by the block ticks, which burn it out if it's too often. */
    internal val torchToggles = HashMap<BlockPos, MutableList<Long>>()

    /** The redstone torches, repeaters and comparators due to look again at the power they take, so that none is due twice at once. */
    internal val redstoneDue = HashSet<BlockPos>()

    /** Every comparator, which redstone looks at every tick, since what it reads changes by itself. */
    internal val comparators = HashSet<BlockPos>()

    /** What each comparator that gives any power gives out of its front, by where it is. */
    internal val comparatorPower = HashMap<BlockPos, Int>()

    /** What was on each pressure plate anything was on, when the plates last looked, by where they are. */
    internal var plateLoads: Map<BlockPos, PlateLoad> = emptyMap()

    /** Every beacon, by where it is, with how many layers of pyramid its beam last shone on, or 0 if it didn't. */
    internal val beacons = HashMap<BlockPos, Int>()

    /** The maps players have drawn. */
    val maps = Maps()

    /** The arms of the pistons that have moved, or are moving, by where the pistons are. */
    internal val pistonArms = HashMap<BlockPos, PistonArm>()

    /** The arm of the piston [state] at [pos], in, if it's never moved. */
    internal fun pistonArm(pos: BlockPos, state: BlockState): PistonArm = pistonArms.getOrPut(pos) { PistonArm(state.name == STICKY_PISTON) }

    /**
     * What kind of block [state] is, which a furnace, redstone torch or
     * redstone lamp is whether lit or not, and a repeater or comparator
     * whether powered or not.
     */
    private fun kindOf(state: BlockState) = state.furnaceKind?.block ?: when (state.name) {
        UNLIT_REDSTONE_TORCH -> REDSTONE_TORCH
        LIT_REDSTONE_LAMP -> REDSTONE_LAMP
        POWERED_REPEATER -> UNPOWERED_REPEATER
        POWERED_COMPARATOR -> UNPOWERED_COMPARATOR
        else -> state.name
    }

    /** The items each chest, barrel and shulker box keeps, by where it is, once a player has opened it or put something in it. */
    private val containers = HashMap<BlockPos, Container>()

    /** The furnaces, brewing stands, dispensers and droppers, by where they are, once something has used them, and every hopper. */
    private val machines = HashMap<BlockPos, Machine>()

    /** The items each chest, barrel, shulker box and lectern keeps, and every machine, by where they are, for saving. */
    internal val keptContainers: Map<BlockPos, Container> get() = containers
    internal val keptMachines: Map<BlockPos, Machine> get() = machines

    /** Where every block that keeps data besides its state is. */
    internal val blockEntityPositions: List<BlockPos> get() = blockEntities.values.flatMap { it.keys }

    /** How many times the machines have worked, which hoppers go by to keep in step. */
    private var machineTicks = 0L

    /** The items the chest, barrel or shulker box at [pos] keeps. */
    fun containerAt(pos: BlockPos): Container {
        chunkAt(pos.chunk) // Which puts back what a saved chunk's containers held, first
        return containers.getOrPut(pos) { Container(STORAGE_SLOTS) }
    }

    /** The one slot the lectern at [pos] keeps its book in. */
    internal fun lecternAt(pos: BlockPos): Container {
        chunkAt(pos.chunk)
        return containers.getOrPut(pos) { Container(1) }
    }

    /** The furnace at [pos], or null if there isn't one. */
    fun furnaceAt(pos: BlockPos): Furnace? {
        (machines[pos] as? Furnace)?.let { return it }
        val kind = stateAt(pos)?.furnaceKind ?: return null
        return Furnace(kind).also { machines[pos] = it }
    }

    /** The brewing stand at [pos], or null if there isn't one. */
    fun brewingStandAt(pos: BlockPos): BrewingStand? {
        (machines[pos] as? BrewingStand)?.let { return it }
        if (stateAt(pos)?.name != BREWING_STAND) return null
        return BrewingStand().also { machines[pos] = it }
    }

    /** The hopper at [pos], or null if there isn't one. */
    fun hopperAt(pos: BlockPos): Hopper? {
        (machines[pos] as? Hopper)?.let { return it }
        if (stateAt(pos)?.name != HOPPER) return null
        return Hopper().also { machines[pos] = it }
    }

    /** The dispenser or dropper at [pos], or null if there isn't one. */
    fun dispenserAt(pos: BlockPos): Dispenser? {
        (machines[pos] as? Dispenser)?.let { return it }
        val kind = stateAt(pos)?.dispenserKind ?: return null
        return Dispenser(kind).also { machines[pos] = it }
    }

    /**
     * What a block keeps besides its state, which goes with it as a piston
     * moves it: the data of its block entity, its items, and its machine.
     */
    internal class Kept(val blockEntity: NbtMap?, val container: Container?, val machine: Machine?) {
        /** Every item it keeps, slot by slot, taken out. */
        fun empty(): List<ItemStack?> = container?.empty() ?: machine?.slots?.empty().orEmpty()
    }

    /** Takes what the block at [pos] keeps off it, to go with it elsewhere. */
    internal fun lift(pos: BlockPos) = Kept(blockEntities[pos.chunk]?.remove(pos), containers.remove(pos), machines.remove(pos))

    /** Puts [kept] with the block at [pos], once it's there. */
    internal fun lay(pos: BlockPos, kept: Kept) {
        kept.blockEntity?.let { setBlockEntity(pos, it) }
        kept.container?.let { containers[pos] = it }
        kept.machine?.let { machines[pos] = it }
    }

    /** The crafter at [pos], or null if there isn't one. */
    fun crafterAt(pos: BlockPos): Crafter? {
        (machines[pos] as? Crafter)?.let { return it }
        if (stateAt(pos)?.isCrafter != true) return null
        return Crafter().also { machines[pos] = it }
    }

    /** Takes out every item the block at [pos] kept, as it breaks, slot by slot. */
    fun emptyContainer(pos: BlockPos): List<ItemStack?> = (containers.remove(pos)?.empty() ?: (machines[pos])?.slots?.empty()).orEmpty()

    /** Takes out the experience the furnace at [pos] has stored, as it breaks, and forgets the furnace. */
    fun takeExperience(pos: BlockPos): Float = (machines.remove(pos) as? Furnace)?.takeExperience() ?: 0f

    /**
     * Works every furnace, brewing stand, hopper, dispenser, dropper and
     * crafter a tick, by [recipes], burning [fuel]. Returns where blocks
     * changed how they look: a furnace lit or went out, a brewing stand's
     * bottles changed, or a crafter crafted. Brewing stands that finished a
     * brew are in [brewed], the dispensers and droppers that fired in
     * [fired], and the sounds crafters made in [sounds].
     */
    fun tickMachines(
        recipes: Recipes,
        fuel: Fuel,
        brewed: MutableList<BlockPos> = mutableListOf(),
        fired: MutableList<Fired> = mutableListOf(),
        sounds: MutableList<BlockSound> = mutableListOf(),
    ): List<BlockPos> {
        val changed = mutableListOf<BlockPos>()
        machineTicks++
        for ((pos, machine) in machines.toList()) {
            val state = stateAt(pos) ?: continue
            when (machine) {
                is Furnace -> if (machine.tick(recipes, fuel, ::newStackId)) {
                    val name = if (machine.lit) machine.kind.litBlock else machine.kind.block
                    blocks.find(name, state.properties)?.let { set(pos, it); changed += pos }
                }
                is BrewingStand -> {
                    if (machine.tick(recipes, ::newStackId)) brewed += pos
                    val bits = BOTTLE_BITS.map { (slot, bit) -> bit to bit(slot in machine.bottlesShown) }.toMap()
                    if (bits.any { (bit, value) -> state.properties[bit] != value }) {
                        blocks.find(state.name, state.properties + bits)?.let { set(pos, it); changed += pos }
                    }
                }
                is Hopper -> tickHopper(pos, machine, machineTicks, recipes, fuel)
                is Dispenser -> if (machine.fireIn > 0 && --machine.fireIn == 0) fire(pos, machine, recipes, fuel)?.let(fired::add)
                is Crafter -> if (tickCrafter(pos, machine, state, recipes, fuel, sounds)) changed += pos
            }
        }
        return changed
    }

    /** The data the block at [pos] keeps beyond its state, like a bed's colour, or null for none. */
    fun blockEntityAt(pos: BlockPos): NbtMap? {
        chunkAt(pos.chunk)
        return blockEntities[pos.chunk]?.get(pos)
    }

    /** Gives the block at [pos] [data] to keep, or forgets what it kept, for null. The data names where it is. */
    fun setBlockEntity(pos: BlockPos, data: NbtMap?) {
        if (data == null) {
            blockEntities[pos.chunk]?.remove(pos)
            return
        }
        val located = data.toBuilder().putInt("x", pos.x).putInt("y", pos.y).putInt("z", pos.z).build()
        blockEntities.getOrPut(pos.chunk) { HashMap() }[pos] = located
    }

    /** The block entities in the chunk at [pos], for sending with it. */
    fun blockEntitiesIn(pos: ChunkPos): Collection<NbtMap> {
        chunkAt(pos)
        return blockEntities[pos]?.values.orEmpty()
    }

    companion object {
        /** The vanilla flat world's layers, from the bottom up: bedrock, two layers of dirt and grass on top. */
        fun vanillaLayers(blocks: BlockPalette) = listOf(
            blocks.stateOf("minecraft:bedrock", mapOf("infiniburn_bit" to 0.toByte())),
            blocks.stateOf("minecraft:dirt"),
            blocks.stateOf("minecraft:dirt"),
            blocks.stateOf("minecraft:grass_block"),
        )

        /** The vanilla flat world, all plains, saved nowhere. */
        fun vanilla(blocks: BlockPalette) = FlatWorld(blocks, vanillaLayers(blocks))
    }
}
