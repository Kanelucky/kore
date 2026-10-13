package org.koremc.world

import org.koremc.protocol.ContainerType
import org.koremc.registry.BlockState
import org.koremc.registry.Fuel
import org.koremc.registry.ItemStack
import org.koremc.registry.Recipes
import kotlin.random.Random

/**
 * The blocks that fire items out of their front when power reaches them:
 * a dispenser, which uses some items as well as dropping them, and a
 * dropper, which drops them, or passes them into the container it faces.
 */
enum class DispenserKind(val block: String, val window: Int) {
    DISPENSER("minecraft:dispenser", ContainerType.DISPENSER),
    DROPPER("minecraft:dropper", ContainerType.DROPPER),
}

/** The kind of dispenser this is, or null if it isn't one. */
val BlockState.dispenserKind: DispenserKind? get() = DispenserKind.entries.find { it.block == name }

/** How many slots a dispenser or dropper has: three by three. */
const val DISPENSER_SLOTS = 9

/** A dispenser's state for having been powered, which it keeps until the power goes, so that it fires only once for it. */
internal const val TRIGGERED = "triggered_bit"

/** How long after power reaches it a dispenser fires, in ticks. */
internal const val DISPENSE_DELAY = 4

/** How far an item a dispenser drops starts out from its middle, just past its front. */
private const val DISPENSE_OUT = 0.7

/** How far off true an item a dispenser drops flies, at most, each way. */
private const val DISPENSE_SPREAD = 0.103365

/** A dispenser or dropper of [kind], and whether it's about to fire. */
class Dispenser(val kind: DispenserKind) : Machine(DISPENSER_SLOTS) {
    /** Ticks until it fires, now that power has reached it, or 0 if it isn't about to. */
    internal var fireIn = 0
}

/**
 * A dispenser or dropper at [pos] firing out of its [facing] side, as
 * players see and hear it: an item coming out, with smoke, or, with
 * nothing in it to fire, a click.
 */
data class Fired(val pos: BlockPos, val facing: Face, val dispensed: Boolean)

/**
 * Items a dispenser does more with than drop, which wait in it until Kore
 * does that too: what it shoots, like arrows and potions, places, like TNT
 * and shulker boxes, or uses on what's in front of it, like bone meal and
 * shears. Spawn eggs and full buckets wait too.
 */
private val HELD = setOf(
    "arrow", "egg", "snowball", "experience_bottle", "splash_potion", "lingering_potion", "firework_rocket", "fire_charge",
    "wind_charge", "trident", "tnt", "flint_and_steel", "bone_meal", "shears", "brush", "armor_stand", "carved_pumpkin",
    "wither_skeleton_skull",
)
private val HELD_ENDINGS = listOf("_spawn_egg", "shulker_box")

/**
 * Fires the dispenser or dropper at [pos], once the power that reached it
 * has had time to: one item, from a slot picked at random of those with
 * any, goes out of its front. A dropper passes it into the container it
 * faces, if there is one, by the face it reaches, as a hopper would, and
 * keeps it while that's full. Otherwise the item drops out, flying from
 * the front. A dispenser drops what Kore doesn't do more with yet, and
 * keeps the rest, as well as a minecart with a rail in front of it. With
 * nothing in it, it clicks. Returns what players see and hear, if
 * anything: passing an item into a container is quiet.
 */
internal fun FlatWorld.fire(pos: BlockPos, dispenser: Dispenser, recipes: Recipes, fuel: Fuel): Fired? {
    val state = stateAt(pos) ?: return null
    val facing = Face.of(state.properties[FACING_DIRECTION] as? Int ?: Face.DOWN.ordinal) ?: Face.DOWN
    val slot = (0 until DISPENSER_SLOTS).filter { dispenser.slots[it] != null }.randomOrNull() ?: return Fired(pos, facing, dispensed = false)
    val there = dispenser.slots[slot]!!
    val front = pos.side(facing)
    if (dispenser.kind == DispenserKind.DROPPER) {
        val target = hopperReach(front, facing.opposite, recipes, fuel)
        if (target != null) {
            if (passOne(target, there.stack)) dispenser.slots[slot] = there.less(1)
            return null
        }
    } else if (!dropsAsIs(there.stack, front)) {
        return null
    }
    dispenser.slots[slot] = there.less(1)
    dropOut(pos, facing, there.stack.copy(count = 1))
    return Fired(pos, facing, dispensed = true)
}

/** Whether a dispenser drops [stack] as it is, with the block at [front] in front of it, rather than doing more with it. */
private fun FlatWorld.dropsAsIs(stack: ItemStack, front: BlockPos): Boolean {
    val name = stack.item.name.removePrefix("minecraft:")
    return when {
        name in HELD || HELD_ENDINGS.any(name::endsWith) -> false
        name.endsWith("_bucket") -> name == "milk_bucket"
        name.endsWith("minecart") -> stateAt(front)?.name?.endsWith("rail") != true
        else -> true
    }
}

/**
 * Drops [stack] out of the [facing] side of the block at [pos], as
 * dispensers and droppers do: from just past its front, flying on at a
 * fifth to three tenths of a block a tick, up at a fifth, and a little
 * off true each way.
 */
private fun FlatWorld.dropOut(pos: BlockPos, facing: Face, stack: ItemStack) {
    val position = Vec3(
        (pos.x + 0.5 + facing.dx * DISPENSE_OUT).toFloat(),
        (pos.y + 0.5 + facing.dy * DISPENSE_OUT - if (facing.dy != 0) 0.125 else 0.15625).toFloat(),
        (pos.z + 0.5 + facing.dz * DISPENSE_OUT).toFloat(),
    )
    val speed = Random.nextDouble() * 0.1 + 0.2
    fun offTrue(mean: Double) = (mean + DISPENSE_SPREAD * (Random.nextDouble() - Random.nextDouble())).toFloat()
    spawnItem(stack, position, Vec3(offTrue(facing.dx * speed), offTrue(0.2), offTrue(facing.dz * speed)), DROPPED_PICKUP_DELAY)
}

/**
 * Which way smoke puffs out of a dispenser firing out of its [facing]
 * side, as the client numbers it: across in threes from north-west to
 * south-east, with 4 for up or down.
 */
fun smokeDirection(facing: Face) = facing.dx + 1 + (facing.dz + 1) * 3
