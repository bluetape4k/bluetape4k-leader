plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.virtualthread.VirtualThreadRunnerDemo")
}

dependencies {
    implementation(project(":bluetape4k-leader-core"))

    implementation(bt4k.bluetape4k.logging)

    runtimeOnly(bt4k.logback)

    testImplementation(bt4k.bluetape4k.junit5)

    testRuntimeOnly(bt4k.logback)
}
