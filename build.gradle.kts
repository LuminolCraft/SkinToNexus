plugins {
    kotlin("jvm") version "2.4.20"
    id("com.gradleup.shadow") version "9.6.1"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
}

kotlin {
    jvmToolchain(25)
}

tasks {
    build {
        dependsOn(shadowJar)
    }


    processResources {
        val props = mapOf("version" to version)
        filesMatching("velocity-plugin.json") {
            expand(props)
        }
    }
}
