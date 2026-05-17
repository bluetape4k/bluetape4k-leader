plugins {
    application
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot4)
}

apply(plugin = "org.springframework.boot.aot")

application {
    mainClass.set("io.bluetape4k.leader.examples.prometheus.PrometheusDashboardApp")
}

springBoot {
    mainClass.set("io.bluetape4k.leader.examples.prometheus.PrometheusDashboardApp")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.boot4.dependencies.get().toString())
        // Spring Boot's BOM pins an older Kotlin line; import Kotlin BOM later.
        mavenBom(libs.kotlin.bom.get().toString())
    }
}

dependencies {
    implementation(project(":bluetape4k-leader-spring-boot"))
    implementation(project(":bluetape4k-leader-micrometer"))
    implementation(project(":bluetape4k-leader-redis-lettuce"))

    implementation(libs.bluetape4k.logging)
    implementation(libs.bluetape4k.testcontainers)
    implementation(libs.lettuce.core)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.spring.tx)
    implementation(libs.testcontainers)

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")

    runtimeOnly(libs.logback)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.awaitility.kotlin)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.test)
    testImplementation(libs.testcontainers.junit.jupiter)
}
