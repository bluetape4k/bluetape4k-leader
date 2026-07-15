plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.strategy.StrategicElectionDemo")
}

dependencies {
    implementation(project(":bluetape4k-leader-core"))

    implementation(bt4k.bluetape4k.logging)

    runtimeOnly(libs.logback)

    testImplementation(bt4k.bluetape4k.junit5)

    testRuntimeOnly(libs.logback)
}
