package org.koremc.world

import kotlin.math.sqrt
import kotlin.random.Random

/** How long an orb lies in the world before it's gone, in ticks: five minutes. */
private const val ORB_LIFETIME = 6000

/** How far below the world an orb falls before it's gone. */
private const val ORB_VOID_DEPTH = 64

// How orbs move each tick: they fall, slow in the air, and slow more along the ground.
private const val ORB_GRAVITY = 0.04
private const val ORB_DRAG = 0.98
private const val ORB_GROUND_FRICTION = 0.6

/** How far away an orb notices a player to fly to, in blocks. */
private const val ORB_FOLLOW_RANGE = 8.0

/** How hard an orb pulls towards the player it follows, at its closest. */
private const val ORB_PULL = 0.2

/** How far above a player's feet an orb aims: halfway up to their eyes. */
private const val ORB_AIM = PLAYER_EYE_HEIGHT / 2

/**
 * The sizes the game splits experience into as it drops it, biggest first.
 * Bigger orbs look bigger.
 */
private val ORB_SIZES = intArrayOf(2477, 1237, 617, 307, 149, 73, 37, 17, 7, 3, 1)

/**
 * An experience orb: [value] experience for the player who picks it up.
 * [position] is the middle of its bottom, and [velocity] is in blocks a
 * tick. It falls and lands like an item, and flies to the nearest player
 * within eight blocks.
 */
class ExperienceOrb(val id: Long, val value: Int, position: Vec3, velocity: Vec3) {
    var position = position
        private set
    var velocity = velocity
        private set

    var onGround = false
        private set

    /** Whether it moved in the last tick, so those who see it need to hear where it is now. */
    var moved = false
        private set

    /** Whether a piston moved it this tick, which holds it up until the piston's done. */
    private var pushed = false

    /**
     * Whether a piston moved it in the last tick, so those who see it need
     * to see it there at once, keeping up with the piston, rather than
     * have it glide there after.
     */
    var snapped = false
        private set

    internal var age = 0

    /** Moves the orb along a tick, pulled towards whichever of [players], by their feet, is nearest. Returns whether it's still there. */
    internal fun tick(world: FlatWorld, players: List<Vec3>): Boolean {
        age++
        if (age >= ORB_LIFETIME || position.y < MIN_Y - ORB_VOID_DEPTH) return false

        var vx = velocity.x.toDouble()
        var vy = velocity.y - if (pushed) 0.0 else ORB_GRAVITY
        var vz = velocity.z.toDouble()
        val target = players.minByOrNull { distanceSquared(it) }
        if (target != null) {
            // The pull grows as the orb nears, from nothing at eight blocks away.
            val dx = (target.x - position.x) / ORB_FOLLOW_RANGE
            val dy = (target.y + ORB_AIM - position.y) / ORB_FOLLOW_RANGE
            val dz = (target.z - position.z) / ORB_FOLLOW_RANGE
            val distance = sqrt(dx * dx + dy * dy + dz * dz)
            if (distance in 1e-6..1.0) {
                val pull = ORB_PULL * (1 - distance) * (1 - distance) / distance
                vx += dx * pull
                vy += dy * pull
                vz += dz * pull
            }
        }

        val start = position
        var box = Box.around(position)
        val movedY = world.clip(box, Axis.Y, vy)
        box = box.shifted(Axis.Y, movedY)
        val movedX = world.clip(box, Axis.X, vx)
        box = box.shifted(Axis.X, movedX)
        val movedZ = world.clip(box, Axis.Z, vz)
        box = box.shifted(Axis.Z, movedZ)

        onGround = vy < 0 && movedY != vy
        if (movedY != vy) vy = 0.0
        if (movedX != vx) vx = 0.0
        if (movedZ != vz) vz = 0.0
        val friction = if (onGround) ORB_DRAG * ORB_GROUND_FRICTION else ORB_DRAG
        velocity = Vec3((vx * friction).toFloat(), (vy * ORB_DRAG).toFloat(), (vz * friction).toFloat())
        position = box.bottom()
        moved = pushed || position != start
        snapped = pushed
        pushed = false
        return true
    }

    /**
     * Pushes it [by] along [axis], as a piston does, as far as the blocks in
     * its way let it, stopping it that way and holding it up for the tick,
     * so it keeps pace with the piston.
     */
    internal fun pushBy(world: FlatWorld, axis: Axis, by: Double) {
        position = world.pushed(position, axis, by)
        velocity = velocity.stoppedAlong(axis)
        pushed = true
    }

    /** How far, squared, the orb is from where it aims at a player standing at [feet], or as far as can be once they're out of its range. */
    private fun distanceSquared(feet: Vec3): Double {
        val dx = (feet.x - position.x).toDouble()
        val dy = (feet.y + ORB_AIM - position.y).toDouble()
        val dz = (feet.z - position.z).toDouble()
        val squared = dx * dx + dy * dy + dz * dz
        return if (squared <= ORB_FOLLOW_RANGE * ORB_FOLLOW_RANGE) squared else Double.MAX_VALUE
    }
}

/**
 * Drops [amount] experience at [position], as orbs of the sizes the game
 * splits it into, each popping out a little way.
 */
fun FlatWorld.dropExperience(position: Vec3, amount: Int) {
    var left = amount
    while (left > 0) {
        val size = ORB_SIZES.first { it <= left }
        val velocity = Vec3(Random.nextDouble(-0.2, 0.2).toFloat(), Random.nextDouble(0.0, 0.4).toFloat(), Random.nextDouble(-0.2, 0.2).toFloat())
        spawnOrb(size, position, velocity)
        left -= size
    }
}

/**
 * Moves every orb along a tick, towards the nearest of [players], by their
 * feet. One that's lain for five minutes, or fallen out of the world, is
 * gone.
 */
fun FlatWorld.tickOrbs(players: List<Vec3>) {
    for (orb in orbs.toList()) {
        if (!orb.tick(this, players)) removeOrb(orb.id)
    }
}

/**
 * Experience that comes as a fraction, like what smelting earns: the whole
 * of it, and one more by the chance of the fraction.
 */
fun wholeExperience(amount: Float): Int {
    val whole = amount.toInt()
    return whole + if (Random.nextFloat() < amount - whole) 1 else 0
}

/** How much experience it takes to go from [level] to the next. */
fun experienceForLevel(level: Int) = when {
    level >= 30 -> 9 * level - 158
    level >= 15 -> 5 * level - 38
    else -> 2 * level + 7
}

/**
 * A player's experience: their level, and how far towards the next they
 * are, from 0 to 1, as the bar shows it.
 */
class PlayerExperience(level: Int = 0, progress: Float = 0f) {
    var level = level
        private set
    var progress = progress
        private set

    /** Adds [points] of experience, which levels up as many times as it fills the bar. Returns whether the level changed. */
    fun add(points: Int): Boolean {
        val before = level
        var into = progress * experienceForLevel(level) + points
        while (into >= experienceForLevel(level)) {
            into -= experienceForLevel(level)
            level++
        }
        progress = (into / experienceForLevel(level)).coerceIn(0f, 1f)
        return level != before
    }

    /**
     * Adds [levels], or takes them away for fewer than none. The bar stays
     * as full, unless that takes away more levels than the player has,
     * which leaves them with no experience at all.
     */
    fun addLevels(levels: Int) {
        val now = level.toLong() + levels
        if (now < 0) progress = 0f
        level = now.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }
}
