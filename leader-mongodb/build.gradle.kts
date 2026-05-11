configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":leader-core"))
    api(libs.mongodb.driver.sync)
    compileOnly(libs.mongodb.driver.kotlin.coroutine)

    testImplementation(libs.bluetape4k.virtualthread.jdk21)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mongodb.driver.kotlin.coroutine)

    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mongodb)

    // T9 PR 4 — Abstract*ContractTest 사용
    testImplementation(testFixtures(project(":leader-core")))
}
