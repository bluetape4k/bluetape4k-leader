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

val bluetape4kDependenciesCatalogRef = providers.gradleProperty("bluetape4kDependenciesCatalogRef")
    .orElse(providers.environmentVariable("BLUETAPE4K_DEPENDENCIES_CATALOG_REF"))
    .orElse("catalog/2026-06-25-01")
    .get()

fun resolveBluetape4kDependenciesCatalogFile(): File {
    providers.gradleProperty("bluetape4kDependenciesCatalogPath")
        .orElse(providers.environmentVariable("BLUETAPE4K_DEPENDENCIES_CATALOG_PATH"))
        .orNull
        ?.let(::file)
        ?.let { return it }

    listOf(
        "../bluetape4k-dependencies/gradle/libs.versions.toml",
        "bluetape4k-dependencies/gradle/libs.versions.toml",
    ).map(::file).firstOrNull { it.isFile }?.let { return it }

    val catalogFile = file(".gradle/bluetape4k-dependencies/libs.versions.toml")
    if (!catalogFile.isFile) {
        catalogFile.parentFile.mkdirs()
        val catalogUrl =
            "https://raw.githubusercontent.com/bluetape4k/bluetape4k-dependencies/$bluetape4kDependenciesCatalogRef/gradle/libs.versions.toml"
        uri(catalogUrl).toURL().openStream().use { input ->
            catalogFile.outputStream().use { output -> input.copyTo(output) }
        }
    }
    return catalogFile
}

val bluetape4kDependenciesCatalogFile = resolveBluetape4kDependenciesCatalogFile()

require(bluetape4kDependenciesCatalogFile.isFile) {
    "bluetape4k-dependencies catalog not found: $bluetape4kDependenciesCatalogFile. " +
        "Checkout bluetape4k-dependencies at the release-train tag or set bluetape4kDependenciesCatalogPath."
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://central.sonatype.com/repository/maven-snapshots/")
    }
    versionCatalogs {
        create("bt4k") {
            from(files(bluetape4kDependenciesCatalogFile))
        }
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
    "bluetape4k-leader-etcd",
    "bluetape4k-leader-consul",
    "bluetape4k-leader-dynamodb",
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
project(":bluetape4k-leader-etcd").projectDir = file("leader-etcd")
project(":bluetape4k-leader-consul").projectDir = file("leader-consul")
project(":bluetape4k-leader-dynamodb").projectDir = file("leader-dynamodb")
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
    "examples:etcd-reconciler",
    "examples:consul-maintenance",
    "examples:dynamodb-export",
    "examples:zookeeper-scheduler",
    "examples:k8s-lease",
    "examples:k8s-operator",
    "examples:rate-limiter",
    "examples:strategic-election",
    "examples:virtual-thread-runner",
    "examples:redisson-watchdog",
)
