package org.koremc.registry

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Path
import kotlin.io.path.isDirectory

/** The game data, checked out from KoreMC/data into the project's `data/` folder, as Kore itself expects it. */
val gameData: Path = Path.of("data")

/** Skips the calling test when there's no game data to check against, rather than failing it. */
fun assumeGameData() = assumeTrue(gameData.isDirectory()) { "No game data in ${gameData.toAbsolutePath()}; clone https://github.com/KoreMC/data there" }
