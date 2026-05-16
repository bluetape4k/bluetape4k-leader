configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":leader-core"))
    api(project(":leader-exposed-core"))

    // Exposed JDBC
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    api(libs.exposed.dao)
    api(libs.exposed.java.time)

    // Coroutines (CancellationException re-throw 보장)
    implementation(libs.kotlinx.coroutines.core)

    // Connection pool + DB drivers
    implementation(libs.hikaricp)
    compileOnly(libs.postgresql)
    compileOnly(libs.mysql.connector.j)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.bluetape4k.virtualthread.jdk21)
    testImplementation(libs.exposed.jdbc.tests)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.h2.v2)
    testImplementation(libs.postgresql)
    testImplementation(libs.mysql.connector.j)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mysql)

    // T10 PR 5 — Abstract*ContractTest 사용 (Issue #79)
    testImplementation(testFixtures(project(":leader-core")))
}
