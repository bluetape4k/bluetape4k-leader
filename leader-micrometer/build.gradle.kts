configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":leader-core"))
    api(Libs.micrometer_core)

    testImplementation(Libs.bluetape4k_junit5)
    testImplementation(Libs.kotlinx_coroutines_test)
    testImplementation(Libs.micrometer_registry_prometheus)
}
