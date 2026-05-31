plugins {
    application
}

application {
    // KtorAppMain 은 `object` + `@JvmStatic main` 이므로 컴파일러가 생성하는 진입 클래스는
    // `KtorAppMain` (Kt 접미사 없음). E1~E5 examples 의 object companion 패턴과 일관성 유지.
    mainClass.set("io.bluetape4k.leader.examples.ktor.KtorAppMain")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencyManagement {
    imports {
        mavenBom(libs.ktor.bom.get().toString())
    }
}

dependencies {
    implementation(project(":bluetape4k-leader-ktor"))
    implementation(project(":bluetape4k-leader-redis-lettuce"))

    implementation(libs.bluetape4k.coroutines)
    implementation(libs.bluetape4k.logging)
    implementation(libs.bluetape4k.lettuce)
    implementation(libs.bluetape4k.ktor.core)
    implementation(libs.lettuce.core)

    implementation(libs.kotlinx.coroutines.core)

    // Ktor 3.x — server + JSON content negotiation
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.jackson)

    // Jackson — Java 8 time types (Instant) + Kotlin module already provided by ktor-serialization-jackson
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Logging
    runtimeOnly(libs.logback)

    // Testcontainers (data layer test container singleton)
    implementation(libs.bluetape4k.testcontainers)
    implementation(libs.testcontainers)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.ktor.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.awaitility.kotlin)
}
