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
    "leader-spring-boot-common",
    "leader-spring-boot3",
    "leader-spring-boot4",
    "leader-micrometer",
)
