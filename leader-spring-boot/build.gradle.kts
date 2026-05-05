plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.spring.boot4) apply false
    // Freefair AspectJ post-compile-weaving (CTW-only — @EnableAspectJAutoProxy 미사용)
    id("io.freefair.aspectj.post-compile-weaving") version "9.5.0"
}

kover {
    reports {
        verify {
            rule {
                bound {
                    // Spring Boot 통합 모듈 — nightly에서 전체 커버리지 측정
                    minValue = 60
                }
            }
        }
    }
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.boot4.dependencies.get().toString())
        // spring-boot-dependencies는 kotlin.version=1.9.25를 강제하므로
        // kotlin-bom을 뒤에서 다시 import하여 프로젝트 Kotlin 버전을 우선시킨다.
        mavenBom(libs.kotlin.bom.get().toString())
    }
    dependencies {
        // mongodb-driver-core 버전을 driver-sync/driver-kotlin-coroutine과 일치시킨다.
        dependency("org.mongodb:mongodb-driver-core:${libs.versions.mongo.driver.get()}")
        dependency("org.mongodb:mongodb-driver-reactivestreams:${libs.versions.mongo.driver.get()}")
        // r2dbc-h2 1.0.x는 h2 2.1.x의 API에 의존 — h2 2.4.x ABI 충돌 회피
        dependency("com.h2database:h2:2.1.214")
    }
}

dependencies {
    api(project(":leader-core"))

    compileOnly(project(":leader-redis-lettuce"))
    compileOnly(project(":leader-redis-redisson"))
    compileOnly(project(":leader-exposed-jdbc"))
    compileOnly(project(":leader-exposed-r2dbc"))
    compileOnly(project(":leader-mongodb"))
    compileOnly(project(":leader-hazelcast"))
    compileOnly(project(":leader-micrometer"))

    compileOnly(libs.lettuce.core)
    compileOnly(libs.redisson)
    compileOnly(libs.mongodb.driver.sync)
    compileOnly(libs.mongodb.driver.kotlin.coroutine)
    compileOnly(libs.hazelcast)

    api(libs.spring.boot.autoconfigure)
    api(libs.spring.aop)
    api(libs.spring.expression)
    api(libs.spring.aspects)
    api(libs.aspectjweaver)
    api(libs.aspectjrt)
    compileOnly(libs.spring.boot.actuator)
    compileOnly(libs.spring.boot.health)
    compileOnly(libs.spring.boot.configuration.processor)
    compileOnly(libs.spring.context)
    compileOnly(libs.spring.tx)

    // Caffeine — LeaderBeanSelector factory cache
    implementation(libs.caffeine)

    // Logging
    implementation(libs.bluetape4k.logging)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.boot.test.autoconfigure)
    testImplementation(libs.spring.test)
    testImplementation(libs.springmockk)
    testImplementation(project(":leader-redis-redisson"))
    testImplementation(project(":leader-redis-lettuce"))
    testImplementation(project(":leader-exposed-jdbc"))
    testImplementation(project(":leader-exposed-r2dbc"))
    testImplementation(project(":leader-mongodb"))
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.testcontainers.toxiproxy)
    testImplementation(libs.r2dbc.h2)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation("org.assertj:assertj-core")
}

tasks.compileJava {
    inputs.files(tasks.processResources)
}
