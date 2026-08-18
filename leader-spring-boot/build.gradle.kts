plugins {
    alias(bt4k.plugins.kotlin.spring)
    alias(bt4k.plugins.kotlin.allopen)
    // Applied (not apply false) so bootJar / processAot tasks are registered.
    // bootJar is disabled below to keep the published artifact a plain jar.
    alias(bt4k.plugins.spring.boot4)
    // Freefair AspectJ post-compile-weaving (CTW-only — @EnableAspectJAutoProxy 미사용)
    alias(bt4k.plugins.aspectj.post.compile.weaving)
}

// org.springframework.boot.aot registers processAot / processTestAot tasks.
apply(plugin = "org.springframework.boot.aot")

// Library module: publish plain jar, not the fat bootJar.
tasks.bootJar { enabled = false }
tasks.jar { enabled = true }
// processAot needs a main class — library modules have none; disable it.
// processTestAot is what we need and is wired via aotTestClasses below.
tasks.named("processAot") { enabled = false }

plugins.withId("org.jetbrains.kotlinx.kover") {
    extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension>("kover") {
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
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    extensions.configure<io.freefair.gradle.plugins.aspectj.AjcAction>("ajc") {
        options.compilerArgs.add("-Xlint:adviceDidNotMatch=ignore")
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
val aotTest = tasks.register<Test>("aotTest") {
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
        mavenBom(bt4k.spring.boot4.dependencies.get().toString())
        // spring-boot-dependencies는 kotlin.version=1.9.25를 강제하므로
        // kotlin-bom을 뒤에서 다시 import하여 프로젝트 Kotlin 버전을 우선시킨다.
        mavenBom("org.jetbrains.kotlin:kotlin-bom:${bt4k.versions.kotlin.get()}")
        // spring-boot-dependencies pins kotlinx-coroutines to 1.10.2, but leader-core
        // is compiled against 1.11.0 which moves Mutex.$default methods to the interface.
        // Override here to prevent NoSuchMethodError at runtime.
        mavenBom("org.jetbrains.kotlinx:kotlinx-coroutines-bom:${bt4k.versions.kotlinx.coroutines.get()}")
    }
    dependencies {
        // mongodb-driver-core 버전을 driver-sync/driver-kotlin-coroutine과 일치시킨다.
        dependency("org.mongodb:mongodb-driver-core:${bt4k.versions.managed.mongodb.driver.sync.h8beacb2ba830.get()}")
        dependency("org.mongodb:mongodb-driver-reactivestreams:${bt4k.versions.managed.mongodb.driver.sync.h8beacb2ba830.get()}")
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
    compileOnly(bt4k.redisson)
    compileOnly(bt4k.mongodb.driver.sync)
    compileOnly(bt4k.mongodb.driver.kotlin.coroutine)
    compileOnly(bt4k.hazelcast)
    compileOnly(libs.aws2.dynamodb)

    api(libs.spring.boot.autoconfigure)
    api(libs.spring.aop)
    api(libs.spring.expression)
    api(libs.spring.aspects)
    api(bt4k.aspectjweaver)
    api(bt4k.aspectjrt)
    compileOnly(libs.spring.boot.actuator)
    compileOnly(libs.spring.boot.health)
    compileOnly(libs.spring.boot.configuration.processor)
    compileOnly(libs.spring.context)
    compileOnly(libs.spring.tx)
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("org.springframework:spring-webflux")
    compileOnly("jakarta.servlet:jakarta.servlet-api")

    compileOnly(libs.kotlinx.coroutines.reactor)

    // Caffeine — LeaderBeanSelector factory cache
    implementation(bt4k.caffeine)

    // Logging
    implementation(bt4k.bluetape4k.logging)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.boot.test.autoconfigure)
    testImplementation("org.springframework.boot:spring-boot-micrometer-observation")
    testImplementation(libs.spring.test)
    testImplementation("org.springframework:spring-webmvc")
    testImplementation("org.springframework:spring-webflux")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testImplementation(bt4k.springmockk)
    testImplementation(bt4k.bluetape4k.virtualthread.jdk25)
    testImplementation(project(":bluetape4k-leader-consul"))
    testImplementation(project(":bluetape4k-leader-dynamodb"))
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.testcontainers.toxiproxy)
    testImplementation(bt4k.r2dbc.h2)

    // Required by Spring Boot's AssertableApplicationContext test API supertype.
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.springframework.boot:spring-boot-starter-actuator")
    testRuntimeOnly("org.springframework.boot:spring-boot-starter-web")
    testRuntimeOnly("org.springframework.boot:spring-boot-starter-webflux")
}

tasks.compileJava {
    inputs.files(tasks.processResources)
}
