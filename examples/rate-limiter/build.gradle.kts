plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.ratelimit.RateLimiterDemo")
}

dependencies {
    implementation(project(":bluetape4k-leader-redis-lettuce"))

    implementation(bt4k.bluetape4k.bucket4j)
    implementation(bt4k.bluetape4k.core)
    implementation(bt4k.bluetape4k.lettuce)
    implementation(bt4k.bluetape4k.logging)
    implementation(bt4k.bluetape4k.testcontainers)
    implementation(libs.bucket4j.jdk17.core)
    implementation(libs.bucket4j.jdk17.lettuce)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.lettuce.core)
    implementation(libs.testcontainers)

    runtimeOnly(libs.logback)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)

    testRuntimeOnly(libs.logback)
}
