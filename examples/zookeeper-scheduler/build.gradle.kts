plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.zookeeperscheduler.ZooKeeperSchedulerDemo")
}

dependencies {
    implementation(project(":bluetape4k-leader-zookeeper"))

    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.logging)
    implementation(libs.bluetape4k.testcontainers)
    implementation(libs.testcontainers)

    runtimeOnly(libs.logback)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)

    testRuntimeOnly(libs.logback)
}
