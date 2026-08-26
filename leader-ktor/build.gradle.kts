configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencyManagement {
    imports {
        mavenBom(bt4k.ktor.bom.get().toString())
    }
}

dependencies {
    api(project(":bluetape4k-leader-core"))

    api(bt4k.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core)

    // Ktor 3.x — application/plugin DSL
    compileOnly(libs.ktor.server.core)

    // Logging
    implementation(bt4k.bluetape4k.logging)

    // Testing
    testImplementation(libs.ktor.server.core)
    testImplementation("io.ktor:ktor-server-auth")
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(bt4k.bluetape4k.ktor.testing)

    testImplementation(project(":bluetape4k-leader-redis-redisson"))
    testImplementation(bt4k.redisson)
    testImplementation(bt4k.bluetape4k.redisson)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.awaitility.kotlin)
}
