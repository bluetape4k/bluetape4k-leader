plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.migration.MigrationGateDemo")
}

dependencies {
    implementation(project(":bluetape4k-leader-exposed-jdbc"))

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.java.time)
    implementation(libs.hikaricp)

    runtimeOnly(libs.h2.v2)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.exposed.jdbc.tests)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.h2.v2)
    testImplementation(libs.postgresql)
    testImplementation(libs.mysql.connector.j)
}
