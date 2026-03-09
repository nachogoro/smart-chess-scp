plugins {
    kotlin("plugin.serialization") version "2.2.20"
}

dependencies {
    implementation(project(":protocol"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
