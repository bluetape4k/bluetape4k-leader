plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.consulmaintenance.ConsulMaintenanceDemo")
}

dependencies {
    implementation(project(":bluetape4k-leader-consul"))

    implementation(bt4k.bluetape4k.core)
    implementation(bt4k.bluetape4k.logging)
    implementation(bt4k.bluetape4k.testcontainers)
    implementation(libs.testcontainers)

    runtimeOnly(libs.logback)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(libs.testcontainers.junit.jupiter)

    testRuntimeOnly(libs.logback)
}
