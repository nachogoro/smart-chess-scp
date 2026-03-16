plugins {
    kotlin("jvm") version "2.2.20" apply false
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    group = "scp"
    version = "0.1.0"

    repositories {
        mavenLocal()
        mavenCentral()
        google()
    }

    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    dependencies {
        "implementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        "testImplementation"(kotlin("test"))
        "testImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
