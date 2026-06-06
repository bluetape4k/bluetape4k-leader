plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.allopen)
    // Applied (not apply false) so bootJar / processAot tasks are registered.
    // bootJar is disabled below to keep the published artifact a plain jar.
    alias(libs.plugins.spring.boot4)
    // Freefair AspectJ post-compile-weaving (CTW-only — @EnableAspectJAutoProxy 미사용)
    id("io.freefair.aspectj.post-compile-weaving") version "9.5.0"
}

// org.springframework.boot.aot registers processAot / processTestAot tasks.
apply(plugin = "org.springframework.boot.aot")

// Library module: publish plain jar, not the fat bootJar.
tasks.bootJar { enabled = false }
tasks.jar { enabled = true }
// processAot needs a main class — library modules have none; disable it.
// processTestAot is what we need and is wired via aotTestClasses below.
tasks.named("processAot") { enabled = false }

kover {
    currentProject {
        sources {
            includedSourceSets.add("main")
        }
    }
    reports {
        filters {
            excludes {
                classes(
                    "*__TestContext*_BeanDefinitions",
                    "*__BeanDefinitions",
                    "*AjcClosure*",
                )
            }
        }
    }
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

// Runs @SpringBootTest classes in Spring AOT mode to validate auto-configuration AOT compatibility.
// processTestAot gracefully skips Testcontainers-backed test classes when Docker is unavailable
// (Spring Boot catches Throwable per class and logs a warning — the task itself does not fail).
// aotTest then runs only the AOT-safe classes in io.bluetape4k.leader.spring.aot package.
//
// Classpath layout:
//   sourceSets["aotTest"].output.classesDirs  — AOT-generated proxy/hint classes (build/classes/java/aotTest)
//   sourceSets.test.runtimeClasspath          — regular test deps + test classes
val aotTest by tasks.registering(Test::class) {
    description = "Validates Spring AOT compatibility of leader-spring-boot auto-configurations"
    group = "verification"
    dependsOn(tasks.named("aotTestClasses"))
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets["aotTest"].output.classesDirs +
                sourceSets.test.get().runtimeClasspath
    jvmArgs("-Dspring.aot.enabled=true")
    filter { includeTestsMatching("io.bluetape4k.leader.spring.aot.*") }
    shouldRunAfter(tasks.test)
}
tasks.check { dependsOn(aotTest) }

dependencyManagement {
    imports {
        mavenBom(libs.spring.boot4.dependencies.get().toString())
        // spring-boot-dependencies는 kotlin.version=1.9.25를 강제하므로
        // kotlin-bom을 뒤에서 다시 import하여 프로젝트 Kotlin 버전을 우선시킨다.
        mavenBom(libs.kotlin.bom.get().toString())
        // spring-boot-dependencies pins kotlinx-coroutines to 1.10.2, but leader-core
        // is compiled against 1.11.0 which moves Mutex.$default methods to the interface.
        // Override here to prevent NoSuchMethodError at runtime.
        mavenBom(libs.kotlinx.coroutines.bom.get().toString())
    }
    dependencies {
        // mongodb-driver-core 버전을 driver-sync/driver-kotlin-coroutine과 일치시킨다.
        dependency("org.mongodb:mongodb-driver-core:${libs.versions.mongo.driver.get()}")
        dependency("org.mongodb:mongodb-driver-reactivestreams:${libs.versions.mongo.driver.get()}")
    }
}

dependencies {
    api(project(":bluetape4k-leader-core"))

    compileOnly(project(":bluetape4k-leader-redis-lettuce"))
    compileOnly(project(":bluetape4k-leader-redis-redisson"))
    compileOnly(project(":bluetape4k-leader-exposed-jdbc"))
    compileOnly(project(":bluetape4k-leader-exposed-r2dbc"))
    compileOnly(project(":bluetape4k-leader-mongodb"))
    compileOnly(project(":bluetape4k-leader-hazelcast"))
    compileOnly(project(":bluetape4k-leader-etcd"))
    compileOnly(project(":bluetape4k-leader-consul"))
    compileOnly(project(":bluetape4k-leader-dynamodb"))
    compileOnly(project(":bluetape4k-leader-micrometer"))

    compileOnly(libs.lettuce.core)
    compileOnly(libs.redisson)
    compileOnly(libs.mongodb.driver.sync)
    compileOnly(libs.mongodb.driver.kotlin.coroutine)
    compileOnly(libs.hazelcast)
    compileOnly(libs.aws2.dynamodb)

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

    compileOnly(libs.kotlinx.coroutines.reactor)

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
    testImplementation(libs.bluetape4k.virtualthread.jdk21)
    testImplementation(project(":bluetape4k-leader-consul"))
    testImplementation(project(":bluetape4k-leader-dynamodb"))
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.testcontainers.toxiproxy)
    testImplementation(libs.r2dbc.h2)

    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.springframework.boot:spring-boot-starter-actuator")
    testRuntimeOnly("org.springframework.boot:spring-boot-starter-web")
}

tasks.compileJava {
    inputs.files(tasks.processResources)
}
