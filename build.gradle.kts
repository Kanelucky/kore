plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    application
}

group = "org.koremc"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

    // Netty (Handles the TCP HTTP Signaling and the UDP WebRTC traffic)
    implementation("io.netty:netty-all:4.1.101.Final")

    // Kotlinx Serialization (To parse the NetherNet HTTP JSON handshakes and the vanilla data)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Cloudburst NBT (Reads the vanilla data files, and writes NBT for packets and block state hashes)
    implementation("org.cloudburstmc:nbt:3.0.5.Final")

    // LevelDB as the game stores worlds, with Mojang's compression, for saving and loading them.
    // The uber jar carries its API and its own copy of Guava, which it needs and doesn't declare.
    implementation("com.hivemc.leveldb:leveldb:1.1.0:uber")

    // Logback for clean server console logging
    implementation("ch.qos.logback:logback-classic:1.4.14")
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass = "org.koremc.MainKt"
}

tasks.test {
    useJUnitPlatform()

    // Some tests check against the game data in data/, so they rerun when it changes. Without it, they're skipped.
    inputs.files(fileTree("data")).withPropertyName("gameData").withPathSensitivity(PathSensitivity.RELATIVE)
}

// The console reads commands from the server's input, which Gradle passes on only if asked.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
