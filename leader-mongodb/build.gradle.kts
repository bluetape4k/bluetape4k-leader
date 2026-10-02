configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":bluetape4k-leader-core"))
    api(bt4k.mongodb.driver.sync)
    compileOnly(bt4k.mongodb.driver.kotlin.coroutine)

    // Micrometer
    compileOnly(libs.micrometer.core)
    compileOnly(bt4k.bluetape4k.micrometer)

    // Coroutines
    compileOnly(bt4k.bluetape4k.coroutines)
    testImplementation(libs.kotlinx.coroutines.test)

    // Testing
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.virtualthread.jdk25)

    // Testcontainers
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mongodb)

    // T9 PR 4 — Abstract*ContractTest 사용
    testImplementation(testFixtures(project(":bluetape4k-leader-core")))
}
