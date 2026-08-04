configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":bluetape4k-leader-core"))
    api(bt4k.jetcd.core)

    implementation(libs.kotlinx.coroutines.jdk8)

    testImplementation(testFixtures(project(":bluetape4k-leader-core")))
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(bt4k.bluetape4k.virtualthread.jdk21)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
}
