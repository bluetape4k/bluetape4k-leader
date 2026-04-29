configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":leader-core"))

    // Exposed core (스키마 정의 — JDBC/R2DBC 드라이버 없음)
    api(Libs.exposed_core)
    api(Libs.exposed_kotlin_datetime)
    compileOnly(Libs.exposed_dao)

    testImplementation(Libs.bluetape4k_junit5)
    testImplementation(Libs.exposed_jdbc)
    testImplementation(Libs.hikaricp)
    testImplementation(Libs.postgresql)
    testImplementation(Libs.testcontainers)
    testImplementation(Libs.testcontainers_junit_jupiter)
    testImplementation(Libs.testcontainers_postgresql)
}
