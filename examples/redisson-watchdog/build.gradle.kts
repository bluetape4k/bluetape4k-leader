plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.redissonwatchdog.RedissonWatchdogDemo")
}

dependencies {
    implementation(project(":bluetape4k-leader-redis-redisson"))

    implementation(libs.bluetape4k.logging)
    implementation(libs.bluetape4k.testcontainers)
    implementation(libs.testcontainers)

    runtimeOnly(libs.logback)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.testcontainers.junit.jupiter)

    testRuntimeOnly(libs.logback)
}
