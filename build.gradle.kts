plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    id("com.gradleup.shadow") version "9.4.2"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

val ktorVersion = "3.1.3"
val jgitVersion = "7.3.0.202506031305-r"
val kotlinxSerializationVersion = "1.8.1"
val kotlinxCoroutinesVersion = "1.10.2"

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")

    // Embedded web server
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-sessions:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")

    // JGit
    implementation("org.eclipse.jgit:org.eclipse.jgit:$jgitVersion")

    // Silence Ktor's logger in plugin context
    implementation("org.slf4j:slf4j-nop:2.0.17")
}

kotlin {
    jvmToolchain(21)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    shadowJar {
        relocate("io.ktor", "io.github.earth1283.loom.shadow.ktor")
        relocate("io.netty", "io.github.earth1283.loom.shadow.netty")
        relocate("org.eclipse.jgit", "io.github.earth1283.loom.shadow.jgit")
        relocate("kotlinx.serialization", "io.github.earth1283.loom.shadow.serialization")
        relocate("kotlinx.coroutines", "io.github.earth1283.loom.shadow.coroutines")
        mergeServiceFiles()
    }

    runServer {
        minecraftVersion("1.21")
        jvmArgs("-Xms2G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
