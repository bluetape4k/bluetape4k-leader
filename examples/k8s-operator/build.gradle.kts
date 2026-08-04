plugins {
    application
    alias(bt4k.plugins.kotlin.spring)
    alias(bt4k.plugins.spring.boot4)
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
        mavenBom(bt4k.spring.boot4.dependencies.get().toString())
        // Spring Boot's BOM pins an older Kotlin line; import Kotlin BOM later.
        mavenBom("org.jetbrains.kotlin:kotlin-bom:${bt4k.versions.kotlin.get()}")
    }
}

val fabric8VertxVersion = bt4k.versions.vertx4.get()
val fabric8NettyVersion = bt4k.versions.netty4.get()

configurations.named("testRuntimeClasspath") {
    // Fabric8 7.7.x uses the Vert.x 4 HTTP client API; keep K3s example tests isolated from repo-wide Vert.x 5.
    resolutionStrategy.eachDependency {
        when (requested.group) {
            "io.netty" -> useVersion(fabric8NettyVersion)
            "io.vertx" -> useVersion(fabric8VertxVersion)
        }
    }
    resolutionStrategy.force(
        "io.netty:netty-all:$fabric8NettyVersion",
        "io.netty:netty-buffer:$fabric8NettyVersion",
        "io.netty:netty-codec:$fabric8NettyVersion",
        "io.netty:netty-codec-dns:$fabric8NettyVersion",
        "io.netty:netty-codec-http:$fabric8NettyVersion",
        "io.netty:netty-codec-http2:$fabric8NettyVersion",
        "io.netty:netty-codec-socks:$fabric8NettyVersion",
        "io.netty:netty-common:$fabric8NettyVersion",
        "io.netty:netty-handler:$fabric8NettyVersion",
        "io.netty:netty-handler-proxy:$fabric8NettyVersion",
        "io.netty:netty-resolver:$fabric8NettyVersion",
        "io.netty:netty-resolver-dns:$fabric8NettyVersion",
        "io.netty:netty-transport:$fabric8NettyVersion",
        "io.vertx:vertx-core:$fabric8VertxVersion",
        "io.vertx:vertx-web-client:$fabric8VertxVersion",
        "io.vertx:vertx-web-common:$fabric8VertxVersion",
    )
}

dependencies {
    implementation(project(":bluetape4k-leader-k8s"))

    implementation(bt4k.bluetape4k.logging)
    implementation(bt4k.fabric8.kubernetes.client)

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")

    runtimeOnly(bt4k.logback)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
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

val k8sTest = tasks.register<Test>("k8sTest") {
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
