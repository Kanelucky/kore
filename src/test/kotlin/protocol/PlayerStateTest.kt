package org.koremc.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PlayerStateTest {
    @Test
    fun `actor data items carry an ID, their type twice, and a value, in order of ID`() {
        val data = SetActorData(
            runtimeId = 1,
            listOf(
                FloatItem(ActorDataId.SCALE, 1f),
                flagsItem(setOf(ActorFlag.ON_FIRE, ActorFlag.SNEAKING)),
                ShortItem(ActorDataId.AIR_SUPPLY, 300),
            ),
        )

        val expected = bytes(0x27, 0x01, 0x03) +
            bytes(0x00, 0x07, 0x07, 0x06) + // Flags, a long: bits 0 and 1 make 3, zigzagged to 6
            bytes(0x07, 0x01, 0x01, 0x2C, 0x01) + // Air supply, a little-endian short: 300
            bytes(0x26, 0x03, 0x03, 0x00, 0x00, 0x80, 0x3F) + // Scale, a float: 1
            bytes(0x00, 0x00, 0x00) // No int or float properties, tick 0

        assertContentEquals(expected, data.encode())
    }

    @Test
    fun `flags are bits of one long`() {
        assertEquals((1L shl 35) or (1L shl 49), flagsItem(setOf(ActorFlag.BREATHING, ActorFlag.HAS_GRAVITY)).value)
    }

    @Test
    fun `attributes give their range twice, once as the default`() {
        val attributes = UpdateAttributes(runtimeId = 1, listOf(Attribute("a", min = 0f, max = 20f, value = 5f, default = 20f)))

        val twenty = bytes(0x00, 0x00, 0xA0, 0x41)
        val expected = bytes(0x1D, 0x01, 0x01) +
            bytes(0, 0, 0, 0) + twenty + bytes(0x00, 0x00, 0xA0, 0x40) + // Min, max and value
            bytes(0, 0, 0, 0) + twenty + twenty + // The defaults
            bytes(0x01, 'a'.code, 0x00, 0x00) // Name, no modifiers, tick 0

        assertContentEquals(expected, attributes.encode())
    }

    @Test
    fun `abilities go out as one base layer that settles all twenty`() {
        val abilities = UpdateAbilities(entityId = 1, PermissionLevel.MEMBER, abilitiesFor(GameMode.SURVIVAL))

        val expected = bytes(0xBB, 0x01) +
            bytes(0x01, 0, 0, 0, 0, 0, 0, 0) + // Entity ID, a little-endian long
            bytes(0x01, 0x00, 0x01) + // Member, the lowest command permissions, one layer
            bytes(0x01, 0x00) + // The base layer
            bytes(0xFF, 0xFF, 0x0F, 0x00) + // Settles abilities 0 to 19
            bytes(0x3F, 0x00, 0x00, 0x00) + // Building, mining, doors, containers and attacking
            bytes(0xCD, 0xCC, 0x4C, 0x3D, 0x00, 0x00, 0x80, 0x3F, 0xCD, 0xCC, 0xCC, 0x3D) // Fly 0.05, vertical 1, walk 0.1

        assertContentEquals(expected, abilities.encode())
    }

    @Test
    fun `game modes decide what a player may do`() {
        assertEquals(emptySet(), abilitiesFor(GameMode.ADVENTURE) intersect setOf(Ability.BUILD, Ability.MINE))
        assertEquals(true, Ability.MAY_FLY in abilitiesFor(GameMode.CREATIVE))
        assertEquals(setOf(Ability.MAY_FLY, Ability.FLYING, Ability.NO_CLIP, Ability.INVULNERABLE), abilitiesFor(GameMode.SPECTATOR))
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
