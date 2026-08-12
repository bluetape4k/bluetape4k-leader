configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":bluetape4k-leader-core"))
    api(libs.micrometer.core)
    api(libs.micrometer.observation)

    testImplementation(bt4k.bluetape4k.virtualthread.jdk25)
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.micrometer.registry.prometheus)
    testImplementation(libs.testcontainers)
}
