package org.koremc.world

import org.cloudburstmc.nbt.NbtMap
import org.koremc.registry.BlockState
import org.koremc.registry.ItemStack
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Where a sign keeps the text on each side, and what each side keeps.
private const val FRONT = "FrontText"
private const val BACK = "BackText"
private const val TEXT = "Text"
private const val COLOUR = "SignTextColor"
private const val GLOWING = "IgnoreLighting"
private const val WAXED = "IsWaxed"

/** A sign's text before it's dyed: black. */
private const val BLACK_TEXT = 0xFF000000.toInt()

/** The most a player can write on a side of a sign, which is more than fits. */
private const val MOST_TEXT = 512

/** Whether this is a sign: standing, on a wall, or hanging. */
val BlockState.isSign get() = name.endsWith("standing_sign") || name.endsWith("wall_sign") || name.endsWith("_hanging_sign")

/** A blank side of a sign, in black, not glowing. */
private fun blankSide() = NbtMap.builder()
    .putString(TEXT, "")
    .putString("TextOwner", "")
    .putInt(COLOUR, BLACK_TEXT)
    .putBoolean(GLOWING, false)
    .putBoolean("HideGlowOutline", false)
    .putBoolean("PersistFormatting", true)
    .build()

/** The block entity of a new sign, [state], blank on both sides, and not waxed. */
internal fun signData(state: BlockState): NbtMap = NbtMap.builder()
    .putString("id", if (state.name.endsWith("_hanging_sign")) "HangingSign" else "Sign")
    .putCompound(FRONT, blankSide())
    .putCompound(BACK, blankSide())
    .putBoolean(WAXED, false)
    .putLong("LockedForEditingBy", -1)
    .build()

/**
 * Whether a player at [from] sees the front of the sign [state] at [pos],
 * rather than its back: the front faces the way the sign was put up,
 * towards whoever put it up, on a wall, or turned to face them on the
 * floor or hanging from a ceiling.
 */
fun facesFront(pos: BlockPos, state: BlockState, from: Vec3): Boolean {
    val turns = state.properties["ground_sign_direction"] as? Int
    val onWall = state.name.endsWith("wall_sign") || (state.name.endsWith("_hanging_sign") && state.properties["attached_bit"] != 1.toByte())
    val (dx, dz) = if (onWall || turns == null) {
        val face = Face.of(state.properties[FACING_DIRECTION] as? Int ?: Face.SOUTH.ordinal) ?: Face.SOUTH
        face.dx.toDouble() to face.dz.toDouble()
    } else {
        // Sixteen turns, from facing south, round through west.
        val angle = turns * PI / 8
        -sin(angle) to cos(angle)
    }
    return (from.x - (pos.x + 0.5)) * dx + (from.z - (pos.z + 0.5)) * dz >= 0
}

/** Whether the sign at [pos] has been waxed, so nothing changes it any more. */
fun FlatWorld.signWaxed(pos: BlockPos) = blockEntityAt(pos)?.getBoolean(WAXED, false) == true

/**
 * Writes what a player wrote on the sign at [pos], as its client sends it
 * back, [edited]: its text on each side, and nothing else of it, so its
 * colours and wax stay as they were. A waxed sign takes no writing, and
 * nor does a block that isn't a sign. Returns whether it took it.
 */
fun FlatWorld.writeSign(pos: BlockPos, edited: NbtMap): Boolean {
    if (stateAt(pos)?.isSign != true || signWaxed(pos)) return false
    val sign = blockEntityAt(pos) ?: signData(stateAt(pos)!!)
    val written = sign.toBuilder()
    for (side in listOf(FRONT, BACK)) {
        val text = edited.getCompound(side, null)?.getString(TEXT, null) ?: continue
        written.putCompound(side, (sign.getCompound(side, null) ?: blankSide()).toBuilder().putString(TEXT, text.take(MOST_TEXT)).build())
    }
    setBlockEntity(pos, written.build())
    return true
}

/**
 * Uses [held] on the side of the sign at [pos] a player sees, the front if
 * [front]: a dye colours its text, a glow ink sac lights it and an ink sac
 * puts it out, each using itself up, and honeycomb waxes the sign so it
 * can't be written on any more. On a waxed sign, nothing works, and it
 * says so. Null if [held] does none of these, or what it would do is done.
 */
fun FlatWorld.markSign(pos: BlockPos, front: Boolean, held: ItemStack?): ItemUsedOn? {
    val sign = blockEntityAt(pos) ?: return null
    if (signWaxed(pos)) return ItemUsedOn(emptyList(), BlockSound(pos, "block.sign.waxed_interact_fail", -1))
    val name = held?.item?.name ?: return null
    val sideKey = if (front) FRONT else BACK
    val side = sign.getCompound(sideKey, null) ?: blankSide()
    val (changed, sound) = when {
        name in DYE_COLOURS -> side.toBuilder().putInt(COLOUR, opaque(DYE_COLOURS.getValue(name))).build() to "sign.dye.use"
        name == "minecraft:glow_ink_sac" -> side.toBuilder().putBoolean(GLOWING, true).build() to "sign.ink_sac.use"
        name == "minecraft:ink_sac" -> side.toBuilder().putBoolean(GLOWING, false).build() to "sign.ink_sac.use"
        name == "minecraft:honeycomb" -> {
            setBlockEntity(pos, sign.toBuilder().putBoolean(WAXED, true).build())
            return ItemUsedOn(listOf(pos), BlockSound(pos, "copper.wax.on", -1), HeldAfter.USED_UP)
        }
        else -> return null
    }
    if (changed == side) return null
    setBlockEntity(pos, sign.toBuilder().putCompound(sideKey, changed).build())
    return ItemUsedOn(listOf(pos), BlockSound(pos, sound, -1), HeldAfter.USED_UP)
}
