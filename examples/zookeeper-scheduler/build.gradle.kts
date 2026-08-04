plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.zookeeperscheduler.ZooKeeperSchedulerDemo")
}

dependencies {
    implementation(project(":bluetape4k-leader-zookeeper"))

    implementation(bt4k.bluetape4k.core)
    implementation(bt4k.bluetape4k.logging)
    implementation(bt4k.bluetape4k.testcontainers)
    implementation(libs.testcontainers)

    runtimeOnly(bt4k.logback)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)

    testRuntimeOnly(bt4k.logback)
}
