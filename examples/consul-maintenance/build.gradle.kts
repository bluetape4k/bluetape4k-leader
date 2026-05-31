plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.consulmaintenance.ConsulMaintenanceDemo")
}

dependencies {
    implementation(project(":bluetape4k-leader-consul"))

    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.logging)
    implementation(libs.bluetape4k.testcontainers)
    implementation(libs.testcontainers)

    runtimeOnly(libs.logback)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.testcontainers.junit.jupiter)

    testRuntimeOnly(libs.logback)
}
