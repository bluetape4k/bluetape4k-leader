configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencyManagement {
    imports {
        mavenBom(libs.ktor.bom.get().toString())
    }
}

dependencies {
    api(project(":leader-core"))

    api(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core)

    // Ktor 3.x — application/plugin DSL
    compileOnly(libs.ktor.server.core)

    // Logging
    implementation(libs.bluetape4k.logging)

    // Testing
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.test.host)

    testImplementation(project(":leader-redis-redisson"))
    testImplementation(libs.redisson)
    testImplementation(libs.bluetape4k.redisson)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.awaitility.kotlin)
}
