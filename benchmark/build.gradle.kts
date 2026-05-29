plugins {
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlinx.benchmark)
}

sourceSets {
    create("benchmark")
    create("kubernetesBenchmark")
}

kotlin {
    target {
        compilations.getByName("benchmark")
            .associateWith(compilations.getByName("main"))
        compilations.getByName("kubernetesBenchmark")
            .associateWith(compilations.getByName("main"))
    }
}

allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

configurations {
    named("benchmarkImplementation") {
        extendsFrom(configurations.getByName("implementation"))
    }
    named("benchmarkRuntimeOnly") {
        extendsFrom(configurations.getByName("runtimeOnly"))
    }
    named("kubernetesBenchmarkImplementation") {
        extendsFrom(configurations.getByName("implementation"))
    }
    named("kubernetesBenchmarkRuntimeOnly") {
        extendsFrom(configurations.getByName("runtimeOnly"))
    }
}

afterEvaluate {
    // Root dependency management keeps Vert.x 5 for etcd; the K8s benchmark needs Fabric8's Vert.x 4 runtime.
    configurations.named("kubernetesBenchmarkRuntimeClasspath") {
        resolutionStrategy.eachDependency {
            when (requested.group) {
                "io.netty" -> useVersion("4.1.133.Final")
                "io.vertx" -> useVersion("4.5.27")
            }
        }
        resolutionStrategy.force(
            "io.netty:netty-all:4.1.133.Final",
            "io.netty:netty-buffer:4.1.133.Final",
            "io.netty:netty-codec:4.1.133.Final",
            "io.netty:netty-codec-dns:4.1.133.Final",
            "io.netty:netty-codec-http:4.1.133.Final",
            "io.netty:netty-codec-http2:4.1.133.Final",
            "io.netty:netty-codec-socks:4.1.133.Final",
            "io.netty:netty-common:4.1.133.Final",
            "io.netty:netty-handler:4.1.133.Final",
            "io.netty:netty-handler-proxy:4.1.133.Final",
            "io.netty:netty-resolver:4.1.133.Final",
            "io.netty:netty-resolver-dns:4.1.133.Final",
            "io.netty:netty-transport:4.1.133.Final",
            "io.vertx:vertx-core:4.5.27",
            "io.vertx:vertx-web-client:4.5.27",
            "io.vertx:vertx-web-common:4.5.27",
        )
    }
}

benchmark {
    configurations {
        named("main") {
            mode = "thrpt"
            warmups = 2
            iterations = 3
            iterationTime = 1
            iterationTimeUnit = "s"
            outputTimeUnit = "s"
            reportFormat = "json"
            advanced("jvmForks", 1)
        }
        register("averageTime") {
            mode = "avgt"
            warmups = 2
            iterations = 3
            iterationTime = 1
            iterationTimeUnit = "s"
            outputTimeUnit = "us"
            reportFormat = "json"
            advanced("jvmForks", 1)
        }
    }
    targets {
        register("benchmark") {
            this as kotlinx.benchmark.gradle.JvmBenchmarkTarget
            jmhVersion = libs.versions.jmh.get()
        }
        register("kubernetesBenchmark") {
            this as kotlinx.benchmark.gradle.JvmBenchmarkTarget
            jmhVersion = libs.versions.jmh.get()
        }
    }
}

dependencies {
    add("benchmarkImplementation", libs.kotlinx.benchmark.runtime)
    add("benchmarkImplementation", libs.kotlinx.benchmark.runtime.jvm)
    add("benchmarkImplementation", libs.jmh.core)

    add("benchmarkImplementation", project(":bluetape4k-leader-core"))
    add("benchmarkImplementation", project(":bluetape4k-leader-redis-lettuce"))
    add("benchmarkImplementation", project(":bluetape4k-leader-redis-redisson"))
    add("benchmarkImplementation", project(":bluetape4k-leader-exposed-jdbc"))
    add("benchmarkImplementation", project(":bluetape4k-leader-exposed-r2dbc"))
    add("benchmarkImplementation", project(":bluetape4k-leader-mongodb"))
    add("benchmarkImplementation", project(":bluetape4k-leader-hazelcast"))
    add("benchmarkImplementation", project(":bluetape4k-leader-zookeeper"))
    add("benchmarkImplementation", project(":bluetape4k-leader-consul"))
    add("benchmarkImplementation", project(":bluetape4k-leader-etcd"))
    add("benchmarkImplementation", project(":bluetape4k-leader-dynamodb"))

    add("benchmarkImplementation", libs.bluetape4k.testcontainers)
    add("benchmarkImplementation", libs.bluetape4k.virtualthread.jdk21)
    add("benchmarkImplementation", libs.h2.v2)
    add("benchmarkImplementation", libs.postgresql)
    add("benchmarkImplementation", libs.mysql.connector.j)
    add("benchmarkImplementation", libs.r2dbc.h2)
    add("benchmarkImplementation", libs.r2dbc.postgresql)
    add("benchmarkImplementation", libs.r2dbc.mysql)
    add("benchmarkImplementation", libs.mongodb.driver.kotlin.coroutine)
    add("benchmarkImplementation", libs.testcontainers)
    add("benchmarkImplementation", libs.testcontainers.mongodb)
    add("benchmarkImplementation", libs.testcontainers.postgresql)
    add("benchmarkImplementation", libs.testcontainers.mysql)
    add("benchmarkImplementation", libs.kotlinx.coroutines.core)

    add("benchmarkRuntimeOnly", libs.logback)
    add("benchmarkRuntimeOnly", libs.jcl.over.slf4j)
    add("benchmarkRuntimeOnly", libs.jul.to.slf4j)
    add("benchmarkRuntimeOnly", libs.log4j.over.slf4j)

    add("kubernetesBenchmarkImplementation", libs.kotlinx.benchmark.runtime)
    add("kubernetesBenchmarkImplementation", libs.kotlinx.benchmark.runtime.jvm)
    add("kubernetesBenchmarkImplementation", libs.jmh.core)

    add("kubernetesBenchmarkImplementation", project(":bluetape4k-leader-core"))
    add("kubernetesBenchmarkImplementation", project(":bluetape4k-leader-k8s"))

    add("kubernetesBenchmarkImplementation", libs.bluetape4k.testcontainers)
    add("kubernetesBenchmarkImplementation", libs.kotlinx.coroutines.core)

    add("kubernetesBenchmarkRuntimeOnly", libs.logback)
    add("kubernetesBenchmarkRuntimeOnly", libs.jcl.over.slf4j)
    add("kubernetesBenchmarkRuntimeOnly", libs.jul.to.slf4j)
    add("kubernetesBenchmarkRuntimeOnly", libs.log4j.over.slf4j)
}
