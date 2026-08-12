configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":bluetape4k-leader-core"))

    implementation(platform(bt4k.bluetape4k.exposed.bom))

    // Exposed core (스키마 정의 — JDBC/R2DBC 드라이버 없음)
    api(bt4k.exposed.core)
    api(bt4k.exposed.java.time)
    compileOnly(libs.exposed.dao)

    // Test — Multi-DB (H2, PostgreSQL, MySQL)
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.virtualthread.jdk25)
    testImplementation(bt4k.bluetape4k.exposed.jdbc.tests)

    testImplementation(bt4k.exposed.jdbc)
    testImplementation(bt4k.hikaricp)

    // H2 (in-memory, 빠른 단위 테스트)
    testImplementation(bt4k.h2.v2)

    // PostgreSQL (Testcontainers)
    testImplementation(bt4k.postgresql)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)

    // MySQL (Testcontainers)
    testImplementation(bt4k.mysql.connector.j)
    testImplementation(libs.testcontainers.mysql)
}
