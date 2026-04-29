configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":leader-core"))
    api(Libs.hazelcast)

    testImplementation(Libs.bluetape4k_junit5)
    testImplementation(Libs.kotlinx_coroutines_test)
    testImplementation(Libs.testcontainers)
    testImplementation(Libs.testcontainers_junit_jupiter)
    testImplementation(Libs.testcontainers_hazelcast)
}
