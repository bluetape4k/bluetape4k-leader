plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.spring.boot3) apply false
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.boot3.dependencies.get().toString())
        // spring-boot-dependencies는 kotlin.version=1.9.25, mongodb 5.5.x를 강제하므로
        // kotlin-bom을 뒤에서 다시 import하여 프로젝트 Kotlin 버전을 우선시킨다.
        mavenBom(libs.kotlin.bom.get().toString())
    }
    dependencies {
        // mongodb-driver-core 버전을 driver-sync/driver-kotlin-coroutine과 일치시킨다.
        dependency("org.mongodb:mongodb-driver-core:${libs.versions.mongo.driver.get()}")
        dependency("org.mongodb:mongodb-driver-reactivestreams:${libs.versions.mongo.driver.get()}")
    }
}

dependencies {
    api(project(":leader-core"))
    api(project(":leader-spring-boot-common"))

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
    // [#41 leader-aop merged] Spring AOP / SpEL / AspectJ — runtime proxy 활성화
    api(libs.spring.aop)
    api(libs.spring.expression)
    api(libs.aspectjweaver)
    compileOnly(libs.spring.boot.actuator)
    compileOnly(libs.spring.boot.configuration.processor)
    compileOnly(libs.spring.context)
    compileOnly(libs.spring.tx)

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
    // r2dbc-h2가 자체 H2 driver를 가져오므로 h2-v2 별도 추가 시 ABI 충돌
    testImplementation(libs.r2dbc.h2)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    // ApplicationContextRunner.run 콜백이 AssertJ AssertProvider 를 노출하므로 필수
    testImplementation("org.assertj:assertj-core")
}

// Spring Boot Configuration Processor
tasks.compileJava {
    inputs.files(tasks.processResources)
}
