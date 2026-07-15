plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.warmer.CachePartitionWarmerDemo")
}

dependencies {
    implementation(project(":bluetape4k-leader-hazelcast"))

    implementation(bt4k.hazelcast)
    implementation(bt4k.bluetape4k.testcontainers)
    implementation(libs.testcontainers)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
}
