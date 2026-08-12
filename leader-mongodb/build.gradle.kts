configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":bluetape4k-leader-core"))
    api(bt4k.mongodb.driver.sync)
    compileOnly(bt4k.mongodb.driver.kotlin.coroutine)
    compileOnly(libs.micrometer.core)

    testImplementation(bt4k.bluetape4k.virtualthread.jdk25)
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(bt4k.mongodb.driver.kotlin.coroutine)

    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mongodb)

    // T9 PR 4 — Abstract*ContractTest 사용
    testImplementation(testFixtures(project(":bluetape4k-leader-core")))
}
