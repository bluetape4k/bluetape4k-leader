configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":leader-core"))
    api(Libs.exposed_core)
    api(Libs.exposed_jdbc)
    compileOnly(Libs.exposed_r2dbc)

    implementation(Libs.hikaricp)

    testImplementation(Libs.bluetape4k_junit5)
    testImplementation(Libs.kotlinx_coroutines_test)
    testImplementation(Libs.exposed_r2dbc)
    testImplementation(Libs.h2)
    testImplementation(Libs.testcontainers)
    testImplementation(Libs.testcontainers_junit_jupiter)
    testImplementation(Libs.testcontainers_postgresql)
    testImplementation(Libs.postgresql)
}
