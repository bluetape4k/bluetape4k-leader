plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.migration.MigrationGateDemo")
}

dependencies {
    implementation(project(":bluetape4k-leader-exposed-jdbc"))

    // Exposed
    implementation(bt4k.exposed.core)
    implementation(bt4k.exposed.jdbc)
    implementation(libs.exposed.dao)
    implementation(bt4k.exposed.java.time)

    // Bluetape4k Exposed
    implementation(bt4k.bluetape4k.exposed.jdbc)
    testImplementation(bt4k.bluetape4k.exposed.jdbc.tests)

    implementation(bt4k.hikaricp)
    runtimeOnly(bt4k.h2.v2)

    // Test & Testcontainers
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mysql)

    // JDBC Drivers
    testImplementation(bt4k.h2.v2)
    testImplementation(bt4k.postgresql)
    testImplementation(bt4k.mysql.connector.j)
}
