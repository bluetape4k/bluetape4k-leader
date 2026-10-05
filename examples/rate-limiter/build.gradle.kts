plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.ratelimit.RateLimiterDemo")
}

dependencies {
    implementation(project(":bluetape4k-leader-redis-lettuce"))

    implementation(bt4k.bluetape4k.core)

    // Bucket4j
    implementation(bt4k.bluetape4k.bucket4j)
    implementation(bt4k.bucket4j.jdk17.core)
    implementation(bt4k.bucket4j.jdk17.lettuce)

    // Lettuce
    implementation(bt4k.bluetape4k.lettuce)
    implementation(libs.lettuce.core)

    // Coroutines
    implementation(bt4k.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core)

    implementation(bt4k.bluetape4k.testcontainers)
    implementation(libs.testcontainers)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(libs.testcontainers.junit.jupiter)
}
