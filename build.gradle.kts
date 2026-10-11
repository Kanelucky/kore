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

    // Kotlinx Serialization (To parse the NetherNet HTTP JSON handshakes)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

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
}
