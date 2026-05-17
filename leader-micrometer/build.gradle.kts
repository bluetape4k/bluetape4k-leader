configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":bluetape4k-leader-core"))
    api(libs.micrometer.core)

    testImplementation(libs.bluetape4k.virtualthread.jdk21)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.micrometer.registry.prometheus)
    testImplementation(libs.testcontainers)
}
