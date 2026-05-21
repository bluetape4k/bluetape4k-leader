repositories {
    gradlePluginPortal()
    mavenCentral()
    google()
}

plugins {
    `kotlin-dsl`
}

dependencies {
    implementation("me.champeau.jmh:me.champeau.jmh.gradle.plugin:0.7.3")

    testImplementation(kotlin("test"))
}

gradlePlugin {
    plugins {
        create("jmhConventions") {
            id = "bluetape4k.jmh-conventions"
            implementationClass = "JmhConventionPlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
    }
}
