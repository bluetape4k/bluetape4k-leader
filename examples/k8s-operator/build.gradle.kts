plugins {
    application
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot4)
}

apply(plugin = "org.springframework.boot.aot")

application {
    mainClass.set("io.bluetape4k.leader.examples.k8soperator.K8sOperatorApp")
}

springBoot {
    mainClass.set("io.bluetape4k.leader.examples.k8soperator.K8sOperatorApp")
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
    implementation(project(":bluetape4k-leader-k8s"))

    implementation(libs.bluetape4k.logging)
    implementation(libs.fabric8.kubernetes.client)

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")

    runtimeOnly(libs.logback)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.awaitility.kotlin)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("k8s")
    }
}

val k8sTest by tasks.registering(Test::class) {
    description = "Runs K3s-backed Kubernetes operator leader-election tests."
    group = LifecycleBasePlugin.VERIFICATION_GROUP

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    shouldRunAfter(tasks.test)

    useJUnitPlatform {
        includeTags("k8s")
    }

    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
}
