configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":leader-core"))

    // Exposed core (스키마 정의 — JDBC/R2DBC 드라이버 없음)
    api(libs.exposed.core)
    api(libs.exposed.java.time)
    compileOnly(libs.exposed.dao)

    // Test — Multi-DB (H2, PostgreSQL, MySQL)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.virtualthread.jdk21)
    testImplementation(libs.bluetape4k.exposed.jdbc.tests)

    testImplementation(libs.exposed.jdbc)
    testImplementation(libs.hikaricp)

    // H2 (in-memory, 빠른 단위 테스트)
    testImplementation(libs.h2.v2)

    // PostgreSQL (Testcontainers)
    testImplementation(libs.postgresql)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)

    // MySQL (Testcontainers)
    testImplementation(libs.mysql.connector.j)
    testImplementation(libs.testcontainers.mysql)
}
