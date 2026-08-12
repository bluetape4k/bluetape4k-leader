plugins {
    alias(bt4k.plugins.kotlin.allopen)
    alias(bt4k.plugins.kotlinx.benchmark)
}

val fabric8NettyVersion = bt4k.versions.netty4.get()
val fabric8VertxVersion = bt4k.versions.vertx4.get()

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
                "io.netty" -> if (!requested.name.startsWith("netty-tcnative")) useVersion(fabric8NettyVersion)
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
            jmhVersion = bt4k.versions.managed.jmh.core.h350a653f63e5.get()
        }
        register("kubernetesBenchmark") {
            this as kotlinx.benchmark.gradle.JvmBenchmarkTarget
            jmhVersion = bt4k.versions.managed.jmh.core.h350a653f63e5.get()
        }
    }
}

dependencies {
    add("benchmarkImplementation", bt4k.kotlinx.benchmark.runtime)
    add("benchmarkImplementation", bt4k.kotlinx.benchmark.runtime.jvm)
    add("benchmarkImplementation", bt4k.jmh.core)
    add("benchmarkImplementation", platform(bt4k.spring.boot4.dependencies))
    add("benchmarkImplementation", platform(libs.kotlin.bom))
    add("benchmarkImplementation", platform(libs.kotlinx.coroutines.bom))

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
    add("benchmarkImplementation", project(":bluetape4k-leader-spring-boot"))
    add("benchmarkImplementation", project(":bluetape4k-leader-micrometer"))

    add("benchmarkImplementation", bt4k.bluetape4k.testcontainers)
    add("benchmarkImplementation", bt4k.bluetape4k.virtualthread.jdk25)
    add("benchmarkImplementation", bt4k.h2.v2)
    add("benchmarkImplementation", bt4k.postgresql)
    add("benchmarkImplementation", bt4k.mysql.connector.j)
    add("benchmarkImplementation", bt4k.r2dbc.h2)
    add("benchmarkImplementation", bt4k.r2dbc.postgresql)
    add("benchmarkImplementation", bt4k.r2dbc.mysql)
    add("benchmarkImplementation", bt4k.mongodb.driver.kotlin.coroutine)
    add("benchmarkImplementation", libs.testcontainers)
    add("benchmarkImplementation", libs.testcontainers.mongodb)
    add("benchmarkImplementation", libs.testcontainers.postgresql)
    add("benchmarkImplementation", libs.testcontainers.mysql)
    add("benchmarkImplementation", libs.kotlinx.coroutines.core)
    add("benchmarkImplementation", libs.kotlinx.coroutines.reactor)

    add("benchmarkRuntimeOnly", bt4k.logback)
    add("benchmarkRuntimeOnly", libs.jcl.over.slf4j)
    add("benchmarkRuntimeOnly", libs.jul.to.slf4j)
    add("benchmarkRuntimeOnly", libs.log4j.over.slf4j)

    add("kubernetesBenchmarkImplementation", bt4k.kotlinx.benchmark.runtime)
    add("kubernetesBenchmarkImplementation", bt4k.kotlinx.benchmark.runtime.jvm)
    add("kubernetesBenchmarkImplementation", bt4k.jmh.core)

    add("kubernetesBenchmarkImplementation", project(":bluetape4k-leader-core"))
    add("kubernetesBenchmarkImplementation", project(":bluetape4k-leader-k8s"))

    add("kubernetesBenchmarkImplementation", bt4k.bluetape4k.testcontainers)
    add("kubernetesBenchmarkImplementation", libs.kotlinx.coroutines.core)

    add("kubernetesBenchmarkRuntimeOnly", bt4k.logback)
    add("kubernetesBenchmarkRuntimeOnly", libs.jcl.over.slf4j)
    add("kubernetesBenchmarkRuntimeOnly", libs.jul.to.slf4j)
    add("kubernetesBenchmarkRuntimeOnly", libs.log4j.over.slf4j)
}
