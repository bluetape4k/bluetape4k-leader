plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.virtualthread.VirtualThreadRunnerDemo")
}

dependencies {
    implementation(project(":bluetape4k-leader-core"))

    implementation(libs.bluetape4k.logging)

    runtimeOnly(libs.logback)

    testImplementation(libs.bluetape4k.junit5)

    testRuntimeOnly(libs.logback)
}
