plugins {
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlinx.benchmark)
}

sourceSets {
    create("benchmark")
}

kotlin {
    target {
        compilations.getByName("benchmark")
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

    add("benchmarkImplementation", libs.bluetape4k.testcontainers)
    add("benchmarkImplementation", libs.bluetape4k.virtualthread.jdk21)
    add("benchmarkImplementation", libs.h2.v2)
    add("benchmarkImplementation", libs.r2dbc.h2)
    add("benchmarkImplementation", libs.mongodb.driver.kotlin.coroutine)
    add("benchmarkImplementation", libs.testcontainers)
    add("benchmarkImplementation", libs.testcontainers.mongodb)
    add("benchmarkImplementation", libs.kotlinx.coroutines.core)

    add("benchmarkRuntimeOnly", libs.logback)
    add("benchmarkRuntimeOnly", libs.jcl.over.slf4j)
    add("benchmarkRuntimeOnly", libs.jul.to.slf4j)
    add("benchmarkRuntimeOnly", libs.log4j.over.slf4j)
}
