plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.zookeeperscheduler.ZooKeeperSchedulerDemo")
}

dependencies {
    implementation(project(":bluetape4k-leader-zookeeper"))

    implementation(bt4k.bluetape4k.core)
    implementation(bt4k.bluetape4k.testcontainers)
    implementation(libs.testcontainers)

    testImplementation(bt4k.bluetape4k.junit5)
}
