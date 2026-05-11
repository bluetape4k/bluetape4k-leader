configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":leader-core"))
    api(libs.bluetape4k.lettuce)
    api(libs.lettuce.core)

    api(libs.bluetape4k.coroutines)
    api(libs.kotlinx.coroutines.reactive)


    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.bluetape4k.virtualthread.jdk21)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)

    // T7 PR 2 — Abstract*ContractTest 사용
    testImplementation(testFixtures(project(":leader-core")))
}
