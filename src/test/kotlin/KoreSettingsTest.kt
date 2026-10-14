package org.koremc

import org.koremc.world.Features
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KoreSettingsTest {
    private val directory = createTempDirectory("kore-settings")
    private val file = directory.resolve("kore.json")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `with no kore json, a new file has Kore's own settings at their defaults, each explained, and reads back the same`() {
        assertEquals(ServerConfig(), ServerConfig().withKoreSettings(file))

        assertEquals(
            """
            {
                // The addresses players reach the server at: IP addresses or host names.
                // Left empty, every address this computer has that others can reach.
                "advertisedAddresses": [],

                // Shown to players, as text the server can't vouch for, the first time they accept its key.
                "operatorDomain": "localhost",

                // What players still on the server are told as it stops.
                "shutdownMessage": "Server closed",

                // The game's systems Kore runs: all on, the game as it is, and all off, blocks that are only blocks.
                // Off, a system doesn't run at all, and its blocks do nothing. The game rules switch off the rest,
                // like the weather with doweathercycle, or blocks' drops with dotiledrops.
                "features": {
                    // Power, and all it works: dust, torches, repeaters, comparators, observers, lamps, pistons,
                    // pressure plates and daylight detectors, and the doors, note blocks, dispensers and hoppers it moves.
                    // Off, levers and buttons still flip, but power goes nowhere.
                    "redstone": true,

                    // Water and lava flowing, what they make where they meet, and concrete powder hardening in water.
                    // Off, they stay as they're put.
                    "liquids": true,

                    // Sand, gravel, anvils and the like falling with nothing under them.
                    // Off, they stay where they are, and hanging dripstone and snow break instead.
                    "fallingBlocks": true,

                    // Random ticks, bone meal and trampling farmland: whatever grows, spreads, dries, decays, melts or weathers.
                    "growth": true,

                    // Furnaces, brewing stands, hoppers, dispensers, droppers, crafters and beacons working.
                    // Off, they keep what's put in them, and do nothing with it.
                    "machines": true
                }
            }

            """.trimIndent(),
            file.readText(),
        )
        assertEquals(ServerConfig(), ServerConfig().withKoreSettings(file))
    }

    @Test
    fun `systems switched off in the features are read, and switches the file lacks are added on`() {
        file.writeText("""{ "features": { "redstone": false, "growth": false } }""")

        val features = ServerConfig().withKoreSettings(file).features

        assertEquals(Features(redstone = false, growth = false), features)
        assertEquals(listOf("redstone", "growth"), features.off)
        val written = file.readText()
        assertTrue("\"redstone\": false" in written && "\"liquids\": true" in written && "\"machines\": true" in written, written)
        assertEquals(features, ServerConfig().withKoreSettings(file).features)
    }

    @Test
    fun `Kore's own settings are read from the file, with comments of any kind and a comma after the last`() {
        file.writeText(
            """
            {
                // Where players find us
                "advertisedAddresses": ["203.0.113.7", "play.example.com"],
                /* Ours */ "operatorDomain": "example.com",
                "shutdownMessage": "Back soon!", // Restarting nightly
            }
            """.trimIndent(),
        )

        val config = ServerConfig().withKoreSettings(file)

        assertEquals(listOf("203.0.113.7", "play.example.com"), config.advertisedAddresses)
        assertEquals("example.com", config.operatorDomain)
        assertEquals("Back soon!", config.shutdownMessage)
    }

    @Test
    fun `a file without some settings has them added at their defaults, keeping what it had`() {
        file.writeText("""{ "someoneElses": 1, "operatorDomain": "example.com" }""")

        val config = ServerConfig().withKoreSettings(file)

        assertEquals("example.com", config.operatorDomain)
        val written = file.readText()
        assertTrue("\"shutdownMessage\": \"Server closed\"" in written && "// What players still on the server" in written, written)
        assertTrue(written.indexOf("advertisedAddresses") < written.indexOf("operatorDomain"), "Kore's in its own order")
        assertTrue(written.indexOf("operatorDomain") < written.indexOf("\"someoneElses\": 1"), "then the rest")
    }

    @Test
    fun `a file Kore can't read stops it, saying why`() {
        file.writeText("""{ "operatorDomain": ["not", "text"] }""")
        assertFailsWith<IllegalStateException> { ServerConfig().withKoreSettings(file) }

        file.writeText("not JSON")
        assertFailsWith<IllegalStateException> { ServerConfig().withKoreSettings(file) }
    }
}
