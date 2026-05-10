plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.batch.BatchSchedulerDemo")
}

dependencies {
    implementation(project(":leader-redis-lettuce"))

    implementation(libs.bluetape4k.lettuce)
    implementation(libs.lettuce.core)
    implementation(libs.bluetape4k.testcontainers)
    implementation(libs.testcontainers)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
}
