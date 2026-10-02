configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":bluetape4k-leader-core"))
    testImplementation(testFixtures(project(":bluetape4k-leader-core")))

    api(bt4k.hazelcast)

    // Coroutines
    implementation(bt4k.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)

    // JUnit5
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.virtualthread.jdk25)

    // Testcontainers
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
}
