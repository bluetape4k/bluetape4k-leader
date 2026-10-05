plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.batch.BatchSchedulerDemo")
}

dependencies {
    implementation(project(":bluetape4k-leader-redis-lettuce"))

    implementation(bt4k.bluetape4k.lettuce)
    implementation(libs.lettuce.core)

    implementation(bt4k.bluetape4k.testcontainers)
    implementation(libs.testcontainers)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(libs.testcontainers.junit.jupiter)
}
