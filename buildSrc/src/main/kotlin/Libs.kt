// @formatter:off

object Plugins {
    object Versions {
        const val dokka = "2.2.0"
        const val detekt = "1.23.8"
        const val dependency_management = "1.1.7"
        const val kover = "0.9.8"
        const val testLogger = "4.0.0"
        const val shadow = "9.3.1"

        const val spring_boot3 = "3.5.14"
        const val spring_boot4 = "4.0.6"

        const val nmcp = "1.4.4"
        const val dependency_check = "12.1.9"
    }

    const val detekt = "io.gitlab.arturbosch.detekt"
    const val dokka = "org.jetbrains.dokka"
    const val dependency_management = "io.spring.dependency-management"
    const val spring_boot = "org.springframework.boot"
    const val kover = "org.jetbrains.kotlinx.kover"
    const val testLogger = "com.adarshr.test-logger"
    const val shadow = "com.gradleup.shadow"
    const val nmcp = "com.gradleup.nmcp"
    const val nmcp_aggregation = "com.gradleup.nmcp.aggregation"
    const val dependency_check = "org.owasp.dependencycheck"
}

object Versions {
    const val kotlin = "2.3.21"
    const val kotlinx_coroutines = "1.10.2"
    const val kotlinx_serialization = "1.11.0"
    const val kotlinx_atomicfu = "0.32.1"

    const val spring_boot3 = Plugins.Versions.spring_boot3
    const val spring_boot4 = Plugins.Versions.spring_boot4

    const val reactor_bom = "2025.0.5"

    const val lettuce = "6.8.2.RELEASE"
    const val redisson = "4.3.1"

    const val exposed = "1.2.0"
    const val r2dbc = "1.0.0.RELEASE"

    const val mongo_driver = "5.6.4"

    const val hazelcast = "5.6.0"

    const val micrometer = "1.16.4"
    const val micrometerTracing = "1.6.4"

    const val slf4j = "2.0.17"
    const val logback = "1.5.32"

    const val junit_jupiter = "6.0.3"
    const val junit_platform = "6.0.3"
    const val kluent = "1.73"
    const val mockk = "1.14.9"
    const val springmockk = "5.0.1"
    const val awaitility = "4.3.0"
    const val testcontainers = "2.0.4"

    const val hikaricp = "6.3.0"
    const val postgresql = "42.7.5"
    const val mysql_connector_j = "9.3.0"
    const val h2 = "2.3.232"
}

object Libs {

    // Kotlin
    const val kotlin_bom = "org.jetbrains.kotlin:kotlin-bom:${Versions.kotlin}"
    const val kotlin_stdlib = "org.jetbrains.kotlin:kotlin-stdlib"
    const val kotlin_reflect = "org.jetbrains.kotlin:kotlin-reflect"
    const val kotlin_test = "org.jetbrains.kotlin:kotlin-test"
    const val kotlin_test_junit5 = "org.jetbrains.kotlin:kotlin-test-junit5"

    // Kotlinx Coroutines
    const val kotlinx_coroutines_bom = "org.jetbrains.kotlinx:kotlinx-coroutines-bom:${Versions.kotlinx_coroutines}"
    const val kotlinx_coroutines_core = "org.jetbrains.kotlinx:kotlinx-coroutines-core"
    const val kotlinx_coroutines_core_jvm = "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm"
    const val kotlinx_coroutines_reactive = "org.jetbrains.kotlinx:kotlinx-coroutines-reactive"
    const val kotlinx_coroutines_reactor = "org.jetbrains.kotlinx:kotlinx-coroutines-reactor"
    const val kotlinx_coroutines_test = "org.jetbrains.kotlinx:kotlinx-coroutines-test"
    const val kotlinx_atomicfu = "org.jetbrains.kotlinx:atomicfu:${Versions.kotlinx_atomicfu}"

    // Jetbrains
    const val jetbrains_annotations = "org.jetbrains:annotations:26.0.2"

    // Logging
    const val slf4j_api = "org.slf4j:slf4j-api:${Versions.slf4j}"
    const val jcl_over_slf4j = "org.slf4j:jcl-over-slf4j:${Versions.slf4j}"
    const val jul_to_slf4j = "org.slf4j:jul-to-slf4j:${Versions.slf4j}"
    const val log4j_over_slf4j = "org.slf4j:log4j-over-slf4j:${Versions.slf4j}"
    const val logback = "ch.qos.logback:logback-classic:${Versions.logback}"
    const val logback_core = "ch.qos.logback:logback-core:${Versions.logback}"

    // bluetape4k
    const val bluetape4k_bom = "io.github.bluetape4k:bluetape4k-bom:1.8.0-SNAPSHOT"
    const val bluetape4k_core = "io.github.bluetape4k:bluetape4k-core"
    const val bluetape4k_coroutines = "io.github.bluetape4k:bluetape4k-coroutines"
    const val bluetape4k_logging = "io.github.bluetape4k:bluetape4k-logging"
    const val bluetape4k_junit5 = "io.github.bluetape4k:bluetape4k-junit5"

    // Redis — Lettuce
    const val lettuce_core = "io.lettuce:lettuce-core:${Versions.lettuce}"

    // Redis — Redisson
    const val redisson = "org.redisson:redisson:${Versions.redisson}"

    // Exposed (JetBrains Kotlin SQL Framework)
    const val exposed_core = "org.jetbrains.exposed:exposed-core:${Versions.exposed}"
    const val exposed_jdbc = "org.jetbrains.exposed:exposed-jdbc:${Versions.exposed}"
    const val exposed_r2dbc = "org.jetbrains.exposed:exposed-r2dbc:${Versions.exposed}"
    const val exposed_dao = "org.jetbrains.exposed:exposed-dao:${Versions.exposed}"
    const val exposed_kotlin_datetime = "org.jetbrains.exposed:exposed-kotlin-datetime:${Versions.exposed}"

    // MongoDB
    const val mongo_bson = "org.mongodb:bson:${Versions.mongo_driver}"
    const val mongodb_driver_sync = "org.mongodb:mongodb-driver-sync:${Versions.mongo_driver}"
    const val mongodb_driver_reactivestreams = "org.mongodb:mongodb-driver-reactivestreams:${Versions.mongo_driver}"
    const val mongodb_driver_kotlin_coroutine = "org.mongodb:mongodb-driver-kotlin-coroutine:${Versions.mongo_driver}"

    // Hazelcast
    const val hazelcast = "com.hazelcast:hazelcast:${Versions.hazelcast}"

    // Micrometer
    const val micrometer_bom = "io.micrometer:micrometer-bom:${Versions.micrometer}"
    const val micrometer_core = "io.micrometer:micrometer-core"
    const val micrometer_registry_prometheus = "io.micrometer:micrometer-registry-prometheus"

    // Spring Boot
    const val spring_boot3_dependencies = "org.springframework.boot:spring-boot-dependencies:${Versions.spring_boot3}"
    const val spring_boot4_dependencies = "org.springframework.boot:spring-boot-dependencies:${Versions.spring_boot4}"
    const val spring_boot_autoconfigure = "org.springframework.boot:spring-boot-autoconfigure"
    const val spring_boot_configuration_processor = "org.springframework.boot:spring-boot-configuration-processor"
    const val spring_boot_test = "org.springframework.boot:spring-boot-test"
    const val spring_context = "org.springframework:spring-context"
    const val spring_tx = "org.springframework:spring-tx"

    // JDBC / Connection Pool
    const val hikaricp = "com.zaxxer:HikariCP:${Versions.hikaricp}"
    const val postgresql = "org.postgresql:postgresql:${Versions.postgresql}"
    const val mysql_connector_j = "com.mysql:mysql-connector-j:${Versions.mysql_connector_j}"
    const val h2 = "com.h2database:h2:${Versions.h2}"

    // R2DBC
    const val r2dbc_spi = "io.r2dbc:r2dbc-spi:${Versions.r2dbc}"
    const val r2dbc_h2 = "io.r2dbc:r2dbc-h2:1.0.0.RELEASE"
    const val r2dbc_postgresql = "org.postgresql:r2dbc-postgresql:1.0.7.RELEASE"

    // Test
    const val junit_bom = "org.junit:junit-bom:${Versions.junit_jupiter}"
    const val junit_jupiter = "org.junit.jupiter:junit-jupiter"
    const val junit_jupiter_api = "org.junit.jupiter:junit-jupiter-api"
    const val junit_jupiter_engine = "org.junit.jupiter:junit-jupiter-engine"
    const val junit_jupiter_params = "org.junit.jupiter:junit-jupiter-params"
    const val junit_platform_launcher = "org.junit.platform:junit-platform-launcher"
    const val junit_platform_engine = "org.junit.platform:junit-platform-engine"

    const val kluent = "org.amshove.kluent:kluent:${Versions.kluent}"
    const val mockk = "io.mockk:mockk:${Versions.mockk}"
    const val springmockk = "com.ninja-squad:springmockk:${Versions.springmockk}"
    const val awaitility_kotlin = "org.awaitility:awaitility-kotlin:${Versions.awaitility}"

    const val testcontainers_bom = "org.testcontainers:testcontainers-bom:${Versions.testcontainers}"
    const val testcontainers = "org.testcontainers:testcontainers"
    const val testcontainers_junit_jupiter = "org.testcontainers:junit-jupiter"
    const val testcontainers_redis = "org.testcontainers:toxiproxy"
    const val testcontainers_mongodb = "org.testcontainers:mongodb"
    const val testcontainers_hazelcast = "org.testcontainers:hazelcast"
    const val testcontainers_postgresql = "org.testcontainers:postgresql"
    const val testcontainers_mysql = "org.testcontainers:mysql"
}
