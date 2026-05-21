plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.ratelimit.RateLimiterDemo")
}

dependencies {
    implementation(project(":bluetape4k-leader-redis-lettuce"))

    implementation(libs.bluetape4k.bucket4j)
    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.lettuce)
    implementation(libs.bluetape4k.logging)
    implementation(libs.bluetape4k.testcontainers)
    implementation(libs.bucket4j.jdk17.core)
    implementation(libs.bucket4j.jdk17.lettuce)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.lettuce.core)
    implementation(libs.testcontainers)

    runtimeOnly(libs.logback)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)

    testRuntimeOnly(libs.logback)
}
