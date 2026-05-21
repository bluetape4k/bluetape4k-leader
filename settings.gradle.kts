pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    plugins {
        id("org.gradle.toolchains.foojay-resolver-convention") version ("1.0.0")
    }
}

rootProject.name = "bluetape4k-leader"

include(
    "bluetape4k-leader-bom",
    "bluetape4k-leader-core",
    "bluetape4k-leader-redis-lettuce",
    "bluetape4k-leader-redis-redisson",
    "bluetape4k-leader-exposed-core",
    "bluetape4k-leader-exposed-jdbc",
    "bluetape4k-leader-exposed-r2dbc",
    "bluetape4k-leader-mongodb",
    "bluetape4k-leader-hazelcast",
    "bluetape4k-leader-zookeeper",
    "bluetape4k-leader-k8s",
    "bluetape4k-leader-spring-boot",
    "bluetape4k-leader-ktor",
    "bluetape4k-leader-micrometer",
    "benchmark",
)
project(":bluetape4k-leader-core").projectDir = file("leader-core")
project(":bluetape4k-leader-redis-lettuce").projectDir = file("leader-redis-lettuce")
project(":bluetape4k-leader-redis-redisson").projectDir = file("leader-redis-redisson")
project(":bluetape4k-leader-exposed-core").projectDir = file("leader-exposed-core")
project(":bluetape4k-leader-exposed-jdbc").projectDir = file("leader-exposed-jdbc")
project(":bluetape4k-leader-exposed-r2dbc").projectDir = file("leader-exposed-r2dbc")
project(":bluetape4k-leader-mongodb").projectDir = file("leader-mongodb")
project(":bluetape4k-leader-hazelcast").projectDir = file("leader-hazelcast")
project(":bluetape4k-leader-zookeeper").projectDir = file("leader-zookeeper")
project(":bluetape4k-leader-k8s").projectDir = file("leader-k8s")
project(":bluetape4k-leader-spring-boot").projectDir = file("leader-spring-boot")
project(":bluetape4k-leader-ktor").projectDir = file("leader-ktor")
project(":bluetape4k-leader-micrometer").projectDir = file("leader-micrometer")
include(
    "examples:batch-scheduler",
    "examples:migration-gate",
    "examples:webhook-poller",
    "examples:cache-warmer",
    "examples:tenant-aggregator",
    "examples:ktor-app",
    "examples:prometheus-dashboard",
    "examples:k8s-lease",
    "examples:k8s-operator",
    "examples:rate-limiter",
)
