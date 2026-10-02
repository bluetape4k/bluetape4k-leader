configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":bluetape4k-leader-core"))
    api(bt4k.curator.recipes)

    testImplementation(testFixtures(project(":bluetape4k-leader-core")))

    // Coroutines
    compileOnly(bt4k.bluetape4k.coroutines)
    testImplementation(libs.kotlinx.coroutines.test)

    // Testing
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.virtualthread.jdk25)

    // Coroutines
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
}
