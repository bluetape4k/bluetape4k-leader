plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.virtualthread.VirtualThreadRunnerDemo")
}

dependencies {
    implementation(project(":bluetape4k-leader-core"))

    implementation(bt4k.bluetape4k.core)
    runtimeOnly(bt4k.bluetape4k.virtualthread.jdk25)

    testImplementation(bt4k.bluetape4k.junit5)
}
