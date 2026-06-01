configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":bluetape4k-leader-core"))
    api(project(":bluetape4k-leader-exposed-core"))

    implementation(platform(libs.bluetape4k.exposed.bom))

    // Exposed R2DBC
    api(libs.exposed.core)
    api(libs.exposed.r2dbc)
    api(libs.exposed.java.time)

    // Coroutines
    api(libs.kotlinx.coroutines.core)
    api(libs.bluetape4k.coroutines)

    // AtomicFU (non-suspend 상태 캐시 변수용)
    api(libs.kotlinx.atomicfu)

    // R2DBC drivers (compileOnly — 런타임은 사용자가 선택)
    compileOnly(libs.r2dbc.postgresql)
    compileOnly(libs.r2dbc.h2)
    compileOnly(libs.r2dbc.mysql)

    // Test
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.exposed.r2dbc.tests)
    testImplementation(libs.kotlinx.coroutines.test)

    // R2DBC drivers (테스트 런타임)
    testImplementation(libs.r2dbc.postgresql)
    testImplementation(libs.r2dbc.h2)
    testImplementation(libs.r2dbc.mysql)

    // Testcontainers (MySQLContainer/PostgreSQLContainer 는 JDBC로 readiness check)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.postgresql)
    testImplementation(libs.mysql.connector.j)

    // T11 PR 6 — Abstract*ContractTest 사용 (Issue #79)
    testImplementation(testFixtures(project(":bluetape4k-leader-core")))
}
