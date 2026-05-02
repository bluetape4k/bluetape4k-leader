configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":leader-core"))

    // Exposed core (스키마 정의 — JDBC/R2DBC 드라이버 없음)
    api(libs.exposed.core)
    api(libs.exposed.kotlin.datetime)
    compileOnly(libs.exposed.dao)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.exposed.jdbc)
    testImplementation(libs.hikaricp)
    testImplementation(libs.postgresql)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
}
