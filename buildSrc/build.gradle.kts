repositories {
    gradlePluginPortal()
    mavenCentral()
    google()
}

plugins {
    `kotlin-dsl`
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("io.github.bluetape4k:bluetape4k-assertions:2.0.0")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}
