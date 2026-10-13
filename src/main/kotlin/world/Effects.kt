package org.koremc.world

/** The effects Kore gives, by the numbers the game gives them. */
object Effect {
    const val SPEED = 1
    const val HASTE = 3
    const val STRENGTH = 5
    const val JUMP_BOOST = 8
    const val REGENERATION = 10
    const val RESISTANCE = 11
}

/** An effect at a strength: its amplifier, which is 0 for level I. */
data class EffectLevel(val effect: Int, val amplifier: Int = 0)

/** An effect on a player: how strong, how many ticks it has left, and whether it's ambient, as a beacon's is. */
class ActiveEffect(val amplifier: Int, var ticksLeft: Int, val ambient: Boolean)

/** The effects on one player, which wear off as they tick. */
class Effects {
    private val active = HashMap<Int, ActiveEffect>()

    operator fun get(effect: Int): ActiveEffect? = active[effect]

    /** The level of [effect] on the player, from I, or 0 if they don't have it. */
    fun level(effect: Int): Int = active[effect]?.let { it.amplifier + 1 } ?: 0

    /**
     * Gives the player [given] for [ticks]. One stronger than what they
     * have of it takes its place, and one as strong lasting longer than
     * what's left of theirs lengthens it. Returns whether that changed
     * anything.
     */
    fun give(given: EffectLevel, ticks: Int, ambient: Boolean = false): Boolean {
        val had = active[given.effect]
        if (had != null && (had.amplifier > given.amplifier || (had.amplifier == given.amplifier && had.ticksLeft >= ticks))) return false
        active[given.effect] = ActiveEffect(given.amplifier, ticks, ambient)
        return true
    }

    /**
     * The effects whose particles show around the player, as the client
     * takes them: up to eight, by number, seven bits each, the effect's
     * number then whether it's ambient.
     */
    fun visible(): Long = active.entries.sortedBy { it.key }.take(MAX_VISIBLE).fold(0L) { packed, (effect, on) ->
        (packed shl 7) or (effect.toLong() shl 1) or (if (on.ambient) 1L else 0L)
    }

    /** Counts every effect down a tick. Returns the ones that wore off. */
    fun tick(): List<Int> {
        val ended = mutableListOf<Int>()
        val each = active.entries.iterator()
        while (each.hasNext()) {
            val (effect, on) = each.next()
            if (--on.ticksLeft <= 0) {
                each.remove()
                ended += effect
            }
        }
        return ended
    }
}

/** The most effects whose particles show around an actor at once. */
private const val MAX_VISIBLE = 8

/** How much faster each level of Haste makes a player mine. */
const val HASTE_PER_LEVEL = 0.2
