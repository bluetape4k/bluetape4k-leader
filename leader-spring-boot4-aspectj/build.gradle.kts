plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.spring.boot4) apply false
    // [T4.2][T4.1a B-1] Freefair AspectJ post-compile-weaving — dependency JAR @Aspect 를 ajc aspectpath 로 사용
    id("io.freefair.aspectj.post-compile-weaving") version "9.5.0"
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
        dependency("org.mongodb:mongodb-driver-core:${libs.versions.mongo.driver.get()}")
        dependency("org.mongodb:mongodb-driver-reactivestreams:${libs.versions.mongo.driver.get()}")
        // [test-scope ABI 충돌 회피] r2dbc-h2 1.0.x ↔ h2 2.4.x mismatch 회피
        dependency("com.h2database:h2:2.1.214")
    }
}

dependencies {
    api(project(":leader-core"))
    api(project(":leader-spring-boot-common"))

    // [T4.1a Option A] common 의 @Aspect 클래스를 Freefair aspectpath 로 weaving
    aspect(project(":leader-spring-boot-common"))

    compileOnly(project(":leader-redis-lettuce"))
    compileOnly(project(":leader-redis-redisson"))
    compileOnly(project(":leader-exposed-jdbc"))
    compileOnly(project(":leader-mongodb"))
    compileOnly(project(":leader-hazelcast"))

    compileOnly(libs.lettuce.core)
    compileOnly(libs.redisson)
    compileOnly(libs.mongodb.driver.sync)
    compileOnly(libs.hazelcast)
    compileOnly(libs.exposed.jdbc)

    api(libs.spring.boot.autoconfigure)
    api(libs.spring.aop)
    api(libs.spring.expression)
    api(libs.spring.aspects)
    api(libs.aspectjweaver)
    api(libs.aspectjrt)
    compileOnly(libs.spring.boot.actuator)
    compileOnly(libs.spring.boot.configuration.processor)
    compileOnly(libs.spring.context)

    // [T4.1a Option A][issue #1050] spring-boot-starter-aop 미추가 (advice 2회 발화 회피)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.kluent)
    testImplementation(libs.mockk)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.boot.test.autoconfigure)
    testImplementation(libs.spring.test)
    testImplementation("org.assertj:assertj-core")
    testImplementation(project(":leader-redis-redisson"))
    testImplementation(libs.redisson)
}
