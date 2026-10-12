package org.koremc.command

import org.koremc.protocol.CommandMessage
import org.koremc.protocol.CommandPermission
import org.koremc.protocol.GameMode
import org.koremc.world.BlockPos
import org.koremc.world.Vec3
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SyntaxTest {
    private data class Player(
        override val name: String,
        override val feet: Vec3,
        override val gameMode: GameMode = GameMode.SURVIVAL,
        override val yaw: Float = 0f,
        override val pitch: Float = 0f,
    ) : Selectable

    private val steve = Player("Steve", Vec3(0f, 0f, 0f))
    private val alex = Player("Alex", Vec3(10f, 0f, 0f), GameMode.CREATIVE)
    private val bob = Player("Bob Builder", Vec3(3f, 0f, 4f))
    private val players = listOf(steve, alex, bob)

    private fun coordinates(text: String) = Coordinates.read(CommandReader(text))

    private fun select(text: String, source: Player? = steve) =
        readTarget(CommandReader(text)).select(players, source, source?.feet ?: Vec3(0f, 0f, 0f), 0f, 0f, Random(1)).map { it.name }

    @Test
    fun `whole numbers across are the middle of the block, and offsets go from where the command runs`() {
        val origin = Vec3(5.25f, 64f, -3.5f)

        assertEquals(Vec3(10.5f, 70f, -2.5f), coordinates("10 70 -3").point(origin, 0f, 0f))
        assertEquals(Vec3(10.25f, 70f, -3.5f), coordinates("10.25 70 ~").point(origin, 0f, 0f))
        assertEquals(Vec3(5.25f, 65f, -5.5f), coordinates("~~1~-2").point(origin, 0f, 0f))
        assertEquals(BlockPos(5, 63, -4), coordinates("~ ~-1 ~").block(origin, 0f, 0f))
    }

    @Test
    fun `coordinates along the way the command faces go left, up and forwards`() {
        // Facing south, along +z, left is east, along +x.
        assertNear(Vec3(2f, 1f, 5f), coordinates("^2 ^1 ^5").point(Vec3(0f, 0f, 0f), 0f, 0f))
        // Facing west, along -x, forwards is west and left is south.
        assertNear(Vec3(-5f, 0f, 2f), coordinates("^2 ^ ^5").point(Vec3(0f, 0f, 0f), 90f, 0f))
        // Looking straight up, forwards is up.
        assertNear(Vec3(0f, 3f, 0f), coordinates("^ ^ ^3").point(Vec3(0f, 0f, 0f), 0f, -90f))
    }

    @Test
    fun `coordinates along the way the command faces can't be mixed with others, and three are needed`() {
        assertFailsWith<SyntaxError> { coordinates("^1 ~ ^") }
        assertFailsWith<SyntaxError> { coordinates("1 2") }
        assertFailsWith<SyntaxError> { coordinates("1 two 3") }
    }

    @Test
    fun `selectors pick out the player running the command, the nearest, everyone, or someone at random`() {
        assertEquals(listOf("Steve"), select("@s"))
        assertEquals(emptyList(), select("@s", source = null))
        assertEquals(listOf("Steve"), select("@p"))
        assertEquals(listOf("Alex"), select("@p", source = alex))
        assertEquals(listOf("Steve", "Alex", "Bob Builder"), select("@a"))
        assertEquals(listOf("Steve", "Alex", "Bob Builder"), select("@e"))
        assertEquals(1, select("@r").size)
    }

    @Test
    fun `selector arguments narrow down who they pick`() {
        assertEquals(listOf("Steve", "Bob Builder"), select("@a[r=5]"))
        assertEquals(listOf("Alex"), select("@a[rm=6]"))
        assertEquals(listOf("Steve", "Bob Builder"), select("@a[c=2]"))
        assertEquals(listOf("Alex"), select("@a[c=-1]"))
        assertEquals(listOf("Alex"), select("@a[m=c]"))
        assertEquals(listOf("Steve", "Bob Builder"), select("@a[m=!creative]"))
        assertEquals(listOf("Bob Builder"), select("@a[name=\"Bob Builder\"]"))
        assertEquals(listOf("Alex"), select("@a[name=!Steve, name=!\"Bob Builder\"]"))
        assertEquals(listOf("Alex"), select("@a[x=10,y=0,z=0,r=1]"))
        assertEquals(listOf("Bob Builder"), select("@a[x=2,y=0,z=3,dx=1,dy=0,dz=1]"))
        assertEquals(emptyList(), select("@a[type=zombie]"))
        assertEquals(emptyList(), select("@a[tag=builder]"))
        assertEquals(3, select("@a[tag=!builder,type=minecraft:player,family=player,l=0]").size)
    }

    @Test
    fun `players are named as they are, or in quotes, whatever the case`() {
        assertEquals(listOf("Alex"), select("alex"))
        assertEquals(listOf("Bob Builder"), select("\"Bob Builder\""))
        assertEquals(emptyList(), select("Notch"))
    }

    @Test
    fun `selectors Kore doesn't know, or that don't close, are syntax errors`() {
        for (text in listOf("@x", "@a[r=five]", "@a[scores={x=1}]", "@a[r=5", "@ab", "@a[hasitem={item=apple}]")) {
            assertFailsWith<SyntaxError>(text) { readTarget(CommandReader(text)) }
        }
    }

    private val source = object : CommandSource {
        override val name = "Steve"
        override val permission = CommandPermission.ANY
        override val origin = Vec3(0f, 0f, 0f)
        override val yaw = 0f
        override val pitch = 0f
    }

    private val ran = mutableListOf<String>()
    private val map = CommandMap(
        listOf(
            command("echo", CommandPermission.ANY, cheat = false, aliases = listOf("repeat")) {
                overload(required("count", IntType), required("word", StringType)) { ran += "count ${int("count")} ${string("word")}" }
                overload(required("word", StringType), optional("loud", EnumType("Boolean", listOf("true", "false")))) { ran += "word ${string("word")} ${stringOrNull("loud")}" }
            },
            command("secret", CommandPermission.ADMIN, cheat = false) { overload { ran += "secret" } },
            command("cheat", CommandPermission.ANY, cheat = true) { overload { ran += "cheat" } },
        ),
        allowCheats = false,
    )

    @Test
    fun `a command runs the first way to write it the line fits`() {
        map.run(source, "/echo 3 times")
        map.run(source, "ECHO hello TRUE")
        map.run(source, "/echo \"two words\"")
        assertEquals(listOf("count 3 times", "word hello true", "word two words null"), ran)
    }

    @Test
    fun `commands someone may not run, or that need cheats, are refused`() {
        assertEquals(listOf(CommandMessage("commands.generic.unknown", false, listOf("secret"))), map.run(source, "/secret").messages)
        assertEquals(listOf(CommandMessage("commands.generic.unknown", false, listOf("nothing"))), map.run(source, "/nothing").messages)
        assertEquals(listOf(CommandMessage("commands.generic.disabled", false)), map.run(source, "/cheat").messages)
        assertTrue(ran.isEmpty())
    }

    @Test
    fun `a syntax error is at the piece furthest along that didn't fit, with ten characters either side`() {
        val result = map.run(source, "/echo 3 times and then some more")
        assertEquals(listOf(CommandMessage("commands.generic.syntax", false, listOf("o 3 times ", "and", " then some"))), result.messages)
    }

    @Test
    fun `failures stop a command, with the message they give`() {
        val failing = CommandMap(listOf(command("fail", CommandPermission.ANY, cheat = false) { overload { success("first"); fail("second", 2) } }), allowCheats = true)
        val result = failing.run(source, "fail")
        assertEquals(listOf(CommandMessage("first", true), CommandMessage("second", false, listOf("2"))), result.messages)
        assertEquals(1, result.successes)
    }

    @Test
    fun `each player's list has the commands they may run, enums once each, and each command's other names with its own`() {
        val available = map.availableTo(CommandPermission.ANY)

        assertEquals(listOf("echo"), available.commands.map { it.name })
        val echo = available.commands.single()
        assertEquals(listOf("echo", "repeat"), available.enums[echo.aliases].values.map { available.enumValues[it] })
        assertEquals(listOf("/echo <count: int> <word: string>", "/echo <word: string> [loud: Boolean]"), map.commands.first().usages)
    }

    private fun assertNear(expected: Vec3, actual: Vec3) =
        assertTrue(listOf(expected.x - actual.x, expected.y - actual.y, expected.z - actual.z).all { abs(it) < 0.0001f }, "$actual isn't $expected")
}
