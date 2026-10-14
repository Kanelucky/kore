package org.koremc.world

/**
 * The game's rules, by the names commands write them, with what each is
 * in a new world: on or off, or a number.
 */
val GAME_RULE_DEFAULTS: Map<String, Any> = linkedMapOf(
    "commandBlockOutput" to true, "commandBlocksEnabled" to true, "doDaylightCycle" to true, "doEntityDrops" to true,
    "doFireTick" to true, "doImmediateRespawn" to false, "doInsomnia" to true, "doLimitedCrafting" to false, "doMobLoot" to true,
    "doMobSpawning" to true, "doTileDrops" to true, "doWeatherCycle" to true, "drowningDamage" to true, "fallDamage" to true,
    "fireDamage" to true, "freezeDamage" to true, "functionCommandLimit" to 10000, "keepInventory" to false,
    "maxCommandChainLength" to 65535, "mobGriefing" to true, "naturalRegeneration" to true, "playersSleepingPercentage" to 100,
    "projectilesCanBreakBlocks" to true, "pvp" to true, "randomTickSpeed" to 1, "recipesUnlock" to true,
    "respawnBlocksExplode" to true, "sendCommandFeedback" to true, "showBorderEffect" to true, "showCoordinates" to false,
    "showDaysPlayed" to false, "showDeathMessages" to true, "showRecipeMessages" to true, "showTags" to true, "spawnRadius" to 5,
    "tntExplodes" to true, "tntExplosionDropDecay" to false,
)

/**
 * The rules that do something in Kore so far, which `/gamerule` offers:
 * those it runs, and those the client draws for itself. The rest are
 * still kept with the world and sent to clients, as the game does, so a
 * world keeps its settings either way.
 */
val GAME_RULES_IN_USE = setOf("doDaylightCycle", "doWeatherCycle", "showCoordinates", "showDaysPlayed")

/**
 * A world's game rules: each on or off, or a number, by its name, which the
 * game keeps in lower case, as it saves them and sends them to clients, and
 * commands write as [GAME_RULE_DEFAULTS] does. They start as [saved] has
 * them, and at their defaults otherwise.
 */
class GameRules(saved: Map<String, Any> = emptyMap()) {
    private val values = LinkedHashMap<String, Any>()

    init {
        for ((name, default) in GAME_RULE_DEFAULTS) {
            val key = name.lowercase()
            values[key] = saved[key]?.takeIf { it::class == default::class } ?: default
        }
    }

    /** Every rule, by the name the game keeps it under, with its value. */
    val all: Map<String, Any> get() = values

    /** The value of the rule called [name], however it's written, or null for no such rule. */
    operator fun get(name: String): Any? = values[name.lowercase()]

    /**
     * Sets the rule called [name] to [value], which must be of its kind.
     * Returns whether it's a rule that took the value.
     */
    fun set(name: String, value: Any): Boolean {
        val key = name.lowercase()
        if (values[key]?.let { it::class == value::class } != true) return false
        values[key] = value
        return true
    }

    /** Whether time passes, day turning to night and back. */
    val doDaylightCycle get() = values["dodaylightcycle"] == true

    /** Whether the weather changes by itself. */
    val doWeatherCycle get() = values["doweathercycle"] == true
}
