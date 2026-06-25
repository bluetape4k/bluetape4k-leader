configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

val fabric8VertxVersion = "4.5.27"
val fabric8NettyVersion = "4.1.133.Final"

configurations.named("testRuntimeClasspath") {
    // Fabric8 7.7.x uses the Vert.x 4 HTTP client API; keep the K3s test runtime isolated from repo-wide Vert.x 5.
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
    api(project(":bluetape4k-leader-core"))
    api(libs.fabric8.kubernetes.client)

    testImplementation(testFixtures(project(":bluetape4k-leader-core")))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("k8s")
    }
}

val k8sTest = tasks.register<Test>("k8sTest") {
    description = "Runs K3s-backed Kubernetes Lease integration tests, including group slot coverage."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    shouldRunAfter(tasks.test)
    useJUnitPlatform {
        includeTags("k8s")
    }
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
}
