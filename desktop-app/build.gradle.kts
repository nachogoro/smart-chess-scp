plugins {
    kotlin("plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.8.0"
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":orchestrator"))
    implementation(project(":board-fake"))
    implementation(project(":game-controller"))
    implementation(project(":remote-lichess"))
    implementation(project(":engine-remote-stockfish"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
}

compose.desktop {
    application {
        mainClass = "scp.desktop.MainKt"
    }
}
