import net.civmc.civgradle.common.util.civRepo

plugins {
    `java-library`
    `maven-publish`
    id("net.civmc.civgradle.plugin") version "1.0.0-SNAPSHOT"
}

allprojects {
    group = "net.civmc.artemis"
    version = "5.0.0-SNAPSHOT"
    description = "Artemis"
}

subprojects {
    apply(plugin = "net.civmc.civgradle.plugin")
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    group = "net.cimc.jukealert"
    version = "3.0.0-SNAPSHOT"

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    repositories {
        mavenCentral()

        mavenLocal() //Temp for zeus for now I think

        maven("https://papermc.io/repo/repository/maven-public/")

        civRepo("CivMC/CivModCore")
    }

    publishing {
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/CivMC/JukeAlert")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
        }
        publications {
            register<MavenPublication>("gpr") {
                from(components["java"])
            }
        }
    }
}
