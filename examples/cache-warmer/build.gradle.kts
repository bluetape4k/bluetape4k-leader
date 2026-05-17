plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.warmer.CachePartitionWarmerDemo")
}

dependencies {
    implementation(project(":bluetape4k-leader-hazelcast"))

    implementation(libs.hazelcast)
    implementation(libs.bluetape4k.testcontainers)
    implementation(libs.testcontainers)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
}
