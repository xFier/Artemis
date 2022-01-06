plugins {
    `java-library`
    id("net.civmc.civgradle.plugin")
    id("io.papermc.paperweight.userdev") version "1.3.1"
}

civGradle {
    paper {
        pluginName = "Artemis"
    }
}

dependencies {
    paperDevBundle("1.18.1-R0.1-SNAPSHOT")

    compileOnly("net.civmc.civmodcore:paper:2.0.0-SNAPSHOT:dev-all")
    compileOnly("com.github.maxopoly:zeus:1.3")
    compileOnly("net.civmc.combattagplus:paper:2.0.0-SNAPSHOT")
    //compileOnly("net.civmc.namelayer:paper:3.0.0-SNAPSHOT:dev")
    //compileOnly("com.gmail.filoghost.holographicdisplays:holographicdisplays-api:2.4.9-SNAPSHOT")
}
