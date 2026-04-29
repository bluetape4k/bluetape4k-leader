configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":leader-core"))
    api(project(":leader-exposed-core"))

    // Exposed R2DBC
    api(Libs.exposed_core)
    api(Libs.exposed_r2dbc)
    api(Libs.exposed_kotlin_datetime)

    // R2DBC PostgreSQL
    compileOnly(Libs.r2dbc_postgresql)

    testImplementation(Libs.bluetape4k_junit5)
    testImplementation(Libs.bluetape4k_testcontainers)
    testImplementation(Libs.kotlinx_coroutines_test)
    testImplementation(Libs.r2dbc_postgresql)
    testImplementation(Libs.testcontainers)
    testImplementation(Libs.testcontainers_junit_jupiter)
    testImplementation(Libs.testcontainers_postgresql)
}
