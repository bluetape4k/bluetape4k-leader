configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":bluetape4k-leader-core"))
    api(project(":bluetape4k-leader-exposed-core"))

    implementation(platform(bt4k.bluetape4k.exposed.bom))

    // Exposed JDBC
    api(bt4k.exposed.core)
    api(bt4k.exposed.jdbc)
    api(libs.exposed.dao)
    api(bt4k.exposed.java.time)

    // Coroutines (CancellationException re-throw 보장)
    implementation(libs.kotlinx.coroutines.core)

    // Connection pool + DB drivers
    implementation(bt4k.hikaricp)
    compileOnly(bt4k.postgresql)
    compileOnly(bt4k.mysql.connector.j)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(bt4k.bluetape4k.virtualthread.jdk21)
    testImplementation(bt4k.bluetape4k.exposed.jdbc.tests)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.h2.v2)
    testImplementation(bt4k.postgresql)
    testImplementation(bt4k.mysql.connector.j)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mysql)

    // T10 PR 5 — Abstract*ContractTest 사용 (Issue #79)
    testImplementation(testFixtures(project(":bluetape4k-leader-core")))
}
