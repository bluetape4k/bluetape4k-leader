configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":bluetape4k-leader-core"))
    api(libs.bluetape4k.redisson)
    api(libs.redisson)

    api(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.bluetape4k.virtualthread.jdk21)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)

    // T8 PR 3 — Abstract*ContractTest 사용
    testImplementation(testFixtures(project(":bluetape4k-leader-core")))
}
