package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.inventory.Container
import org.koremc.registry.AIR_NETWORK_ID
import org.koremc.registry.BlockPalette
import org.koremc.registry.BlockState
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
 * players have changed. Changes last until the server stops, as do the
 * items lying in it, until they're picked up or go.
 *
 * Like the sessions that change it, it runs on the UDP event loop.
 */
class FlatWorld(
    /** Every block state the world can hold, by network ID. */
    val blocks: BlockPalette,
    layers: List<BlockState>,
    biome: Int = PLAINS_BIOME_ID,
) {
    /** Where players appear: standing on the top layer, in the corner of chunk 0, 0. */
    val spawn = BlockPos(0, MIN_Y + layers.size, 0)

    private val generated: Chunk

    /** The chunks players have changed, which no longer match [generated]. */
    private val changed = HashMap<ChunkPos, Chunk>()

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
    private var nextEntityId = 1L

    /** The items lying in the world, oldest first. */
    val items: Collection<ItemEntity> get() = itemEntities.values

    /** The experience orbs in the world, oldest first. */
    val orbs: Collection<ExperienceOrb> get() = orbEntities.values

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

    fun chunkAt(pos: ChunkPos): Chunk = changed[pos] ?: generated

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

    /**
     * Puts [state] at [pos], which must be within the overworld's height.
     * Putting a block of another kind there forgets the block entity the
     * old one had.
     */
    operator fun set(pos: BlockPos, state: BlockState) {
        require(pos.y in MIN_Y..MAX_Y) { "$pos is outside the overworld" }
        // A furnace lighting up, or going out, is the same furnace.
        if (stateAt(pos)?.let(::kindOf) != kindOf(state)) {
            blockEntities[pos.chunk]?.remove(pos)
            containers.remove(pos)
            machines.remove(pos)
            poweredOpen.remove(pos)
            torchToggles.remove(pos)
            redstoneDue.remove(pos)
            comparatorPower.remove(pos)
            pistonArms.remove(pos)
        }
        changed[pos.chunk] = chunkAt(pos.chunk).with(pos, state.networkId)
        // A hopper works from the moment it's there, whether anyone opens it or not.
        if (state.name == HOPPER) machines.getOrPut(pos) { Hopper() }
        if (state.isComparator) comparators += pos else comparators -= pos
        powerChanges += pos
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

    /** The arms of the pistons that have moved, or are moving, by where the pistons are. */
    internal val pistonArms = HashMap<BlockPos, PistonArm>()

    /** The arm of the piston [state] at [pos], in, if it's never moved. */
    internal fun pistonArm(pos: BlockPos, state: BlockState): PistonArm = pistonArms.getOrPut(pos) { PistonArm(state.name == STICKY_PISTON) }

    /** What kind of block [state] is, which a furnace or redstone torch is whether lit or not, and a repeater or comparator whether powered or not. */
    private fun kindOf(state: BlockState) = state.furnaceKind?.block ?: when (state.name) {
        UNLIT_REDSTONE_TORCH -> REDSTONE_TORCH
        POWERED_REPEATER -> UNPOWERED_REPEATER
        POWERED_COMPARATOR -> UNPOWERED_COMPARATOR
        else -> state.name
    }

    /** The items each chest, barrel and shulker box keeps, by where it is, once a player has opened it or put something in it. */
    private val containers = HashMap<BlockPos, Container>()

    /** The furnaces, brewing stands, dispensers and droppers, by where they are, once something has used them, and every hopper. */
    private val machines = HashMap<BlockPos, Machine>()

    /** How many times the machines have worked, which hoppers go by to keep in step. */
    private var machineTicks = 0L

    /** The items the chest, barrel or shulker box at [pos] keeps. */
    fun containerAt(pos: BlockPos): Container = containers.getOrPut(pos) { Container(STORAGE_SLOTS) }

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

    /** Takes out every item the block at [pos] kept, as it breaks, slot by slot. */
    fun emptyContainer(pos: BlockPos): List<ItemStack?> = (containers.remove(pos)?.empty() ?: (machines[pos])?.slots?.empty()).orEmpty()

    /** Takes out the experience the furnace at [pos] has stored, as it breaks, and forgets the furnace. */
    fun takeExperience(pos: BlockPos): Float = (machines.remove(pos) as? Furnace)?.takeExperience() ?: 0f

    /**
     * Works every furnace, brewing stand, hopper, dispenser and dropper a
     * tick, by [recipes], burning [fuel]. Returns where blocks changed how
     * they look: a furnace lit or went out, or a brewing stand's bottles
     * changed. Brewing stands that finished a brew are in [brewed], and the
     * dispensers and droppers that fired in [fired].
     */
    fun tickMachines(recipes: Recipes, fuel: Fuel, brewed: MutableList<BlockPos> = mutableListOf(), fired: MutableList<Fired> = mutableListOf()): List<BlockPos> {
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
            }
        }
        return changed
    }

    /** The data the block at [pos] keeps beyond its state, like a bed's colour, or null for none. */
    fun blockEntityAt(pos: BlockPos): NbtMap? = blockEntities[pos.chunk]?.get(pos)

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
    fun blockEntitiesIn(pos: ChunkPos): Collection<NbtMap> = blockEntities[pos]?.values.orEmpty()

    companion object {
        /** The vanilla flat world: bedrock, two layers of dirt and grass on top, all plains. */
        fun vanilla(blocks: BlockPalette) = FlatWorld(
            blocks,
            listOf(
                blocks.stateOf("minecraft:bedrock", mapOf("infiniburn_bit" to 0.toByte())),
                blocks.stateOf("minecraft:dirt"),
                blocks.stateOf("minecraft:dirt"),
                blocks.stateOf("minecraft:grass_block"),
            ),
        )
    }
}
