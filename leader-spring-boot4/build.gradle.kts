plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.spring.boot4) apply false
    // [#41 leader-aop merged] Freefair AspectJ post-compile-weaving — common 의 @Aspect 클래스를 ajc aspectpath 로 사용
    id("io.freefair.aspectj.post-compile-weaving") version "9.5.0"
}

kover {
    reports {
        verify {
            rule {
                bound {
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
        // spring-boot-dependencies는 kotlin.version=1.9.25, mongodb 5.5.x를 강제하므로
        // kotlin-bom을 뒤에서 다시 import하여 프로젝트 Kotlin 버전을 우선시킨다.
        mavenBom(libs.kotlin.bom.get().toString())
    }
    dependencies {
        // mongodb-driver-core 버전을 driver-sync/driver-kotlin-coroutine과 일치시킨다.
        dependency("org.mongodb:mongodb-driver-core:${libs.versions.mongo.driver.get()}")
        dependency("org.mongodb:mongodb-driver-reactivestreams:${libs.versions.mongo.driver.get()}")
        // [test-scope ABI 충돌 회피]
        // 프로젝트 카탈로그 표준은 h2 2.4.240 (libs.h2.v2)이지만, 본 모듈의 R2DBC 통합 테스트가
        // 사용하는 r2dbc-h2 1.0.x는 h2 2.1.x의 `Session.prepareCommand(String, int)` 시그니처에
        // 의존한다. h2 2.4.x에서 이 메서드 시그니처가 변경되어 r2dbc-h2 호출 시
        // `NoSuchMethodError`가 발생한다. r2dbc-h2 1.1.0이 maven central에 존재하지만
        // h2 2.4.x 호환 여부 미확인이며 사실상 유지보수 정체 상태이다.
        // → 본 모듈 한정 h2 2.1.214 (r2dbc-h2 1.0.x가 자체 의존하는 버전)로 고정.
        // 영향 범위: leader-spring-boot4 testRuntime 만. main classpath 및 다른 모듈 무관.
        // SB3에서는 spring-boot 3.5 BOM이 h2를 강제 upgrade 하지 않아 동일 우회 불필요했음.
        dependency("com.h2database:h2:2.1.214")
    }
}

dependencies {
    api(project(":leader-core"))
    api(project(":leader-spring-boot-common"))

    // [#41 leader-aop merged] common 의 @Aspect 클래스를 Freefair aspectpath 로 weaving
    aspect(project(":leader-spring-boot-common"))

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
    // [#41 leader-aop merged] Spring AOP / SpEL / AspectJ — Boot 4 7.x runtime + Freefair compile-time weaving
    api(libs.spring.aop)
    api(libs.spring.expression)
    api(libs.spring.aspects)
    api(libs.aspectjweaver)
    api(libs.aspectjrt)
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

tasks.compileJava {
    inputs.files(tasks.processResources)
}
