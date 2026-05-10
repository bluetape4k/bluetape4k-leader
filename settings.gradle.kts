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
    "leader-bom",
    "leader-core",
    "leader-redis-lettuce",
    "leader-redis-redisson",
    "leader-exposed-core",
    "leader-exposed-jdbc",
    "leader-exposed-r2dbc",
    "leader-mongodb",
    "leader-hazelcast",
    "leader-zookeeper",
    "leader-spring-boot",
    "leader-ktor",
    "leader-micrometer",
    "examples:batch-scheduler",
    "examples:migration-gate",
    "examples:webhook-poller",
    "examples:cache-warmer",
    "examples:tenant-aggregator",
)
