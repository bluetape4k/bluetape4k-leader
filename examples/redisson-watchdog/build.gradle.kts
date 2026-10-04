plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.redissonwatchdog.RedissonWatchdogDemo")
}

dependencies {
    implementation(project(":bluetape4k-leader-redis-redisson"))

    implementation(bt4k.bluetape4k.core)
    implementation(bt4k.bluetape4k.redisson)

    // Testcontainers
    implementation(bt4k.bluetape4k.testcontainers)
    implementation(libs.testcontainers)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(libs.testcontainers.junit.jupiter)
}
