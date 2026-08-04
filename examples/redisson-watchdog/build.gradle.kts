plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.redissonwatchdog.RedissonWatchdogDemo")
}

dependencies {
    implementation(project(":bluetape4k-leader-redis-redisson"))

    implementation(bt4k.bluetape4k.logging)
    implementation(bt4k.bluetape4k.testcontainers)
    implementation(libs.testcontainers)

    runtimeOnly(bt4k.logback)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(libs.testcontainers.junit.jupiter)

    testRuntimeOnly(bt4k.logback)
}
