configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":bluetape4k-leader-core"))
    api(project(":bluetape4k-leader-exposed-core"))

    implementation(platform(bt4k.bluetape4k.exposed.bom))

    // Exposed R2DBC
    api(bt4k.exposed.core)
    api(bt4k.exposed.r2dbc)
    api(bt4k.exposed.java.time)

    // Coroutines
    api(libs.kotlinx.coroutines.core)
    api(bt4k.bluetape4k.coroutines)

    // AtomicFU (non-suspend 상태 캐시 변수용)
    api(bt4k.kotlinx.atomicfu)

    // R2DBC drivers (compileOnly — 런타임은 사용자가 선택)
    compileOnly(bt4k.r2dbc.postgresql)
    compileOnly(bt4k.r2dbc.h2)
    compileOnly(bt4k.r2dbc.mysql)

    // Test
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(bt4k.bluetape4k.exposed.r2dbc.tests)
    testImplementation(libs.kotlinx.coroutines.test)

    // R2DBC drivers (테스트 런타임)
    testImplementation(bt4k.r2dbc.postgresql)
    testImplementation(bt4k.r2dbc.h2)
    testImplementation(bt4k.r2dbc.mysql)

    // Testcontainers (MySQLContainer/PostgreSQLContainer 는 JDBC로 readiness check)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(bt4k.postgresql)
    testImplementation(bt4k.mysql.connector.j)

    // T11 PR 6 — Abstract*ContractTest 사용 (Issue #79)
    testImplementation(testFixtures(project(":bluetape4k-leader-core")))
}
