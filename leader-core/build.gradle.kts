configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(Libs.bluetape4k_core)
    api(Libs.bluetape4k_logging)

    implementation(Libs.kotlinx_coroutines_core)

    testImplementation(Libs.bluetape4k_junit5)
    testImplementation(Libs.kotlinx_coroutines_test)
}
