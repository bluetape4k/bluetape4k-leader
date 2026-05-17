# bluetape4k-leader-bom

[한국어](./README.ko.md) | English

Maven BOM (Bill of Materials) for the **bluetape4k-leader** ecosystem. Manages versions of all
`io.github.bluetape4k.leader:*` modules so consumers can declare dependencies without specifying
individual versions.

## Architecture

```mermaid
graph TB
    Consumer[Consumer Project]
    BOM[bluetape4k-leader-bom<br/>java-platform]

    subgraph "Core"
      Core[leader-core]
    end

    subgraph "Backends"
      RL[leader-redis-lettuce]
      RR[leader-redis-redisson]
      ExC[leader-exposed-core]
      ExJ[leader-exposed-jdbc]
      ExR[leader-exposed-r2dbc]
      Mongo[leader-mongodb]
      Hzl[leader-hazelcast]
      Zk[leader-zookeeper]
    end

    subgraph "Spring / Metrics / Ktor"
      SB[leader-spring-boot]
      Mm[leader-micrometer]
      Ktor[leader-ktor]
    end

    Consumer -->|platform import| BOM
    BOM -.->|version constraints| Core
    BOM -.->|version constraints| RL
    BOM -.->|version constraints| Mongo
    BOM -.->|version constraints| SB
    BOM -.->|version constraints| Mm
    BOM -.->|version constraints| Ktor
```

The BOM is a Gradle `java-platform` that publishes only `<dependencyManagement>` constraints — no runtime classes.

## Core Features

- Centralized version management for all `bluetape4k-leader` modules
- Single source of truth for distributed leader election stack (blocking / async / coroutine / virtual-thread)
- Aggregated by `bluetape4k-dependencies` for cross-ecosystem version coordination

## Modules Managed

| Module | Description |
|--------|-------------|
| `bluetape4k-leader-core` | Leader election core API (blocking / async / coroutine / virtual-thread) |
| `bluetape4k-leader-redis-lettuce` | Redis backend using Lettuce |
| `bluetape4k-leader-redis-redisson` | Redis backend using Redisson |
| `bluetape4k-leader-exposed-core` | Exposed (RDB) backend core |
| `bluetape4k-leader-exposed-jdbc` | Exposed JDBC backend |
| `bluetape4k-leader-exposed-r2dbc` | Exposed R2DBC backend |
| `bluetape4k-leader-mongodb` | MongoDB backend |
| `bluetape4k-leader-hazelcast` | Hazelcast backend |
| `bluetape4k-leader-zookeeper` | Apache ZooKeeper backend |
| `bluetape4k-leader-spring-boot` | Spring Boot auto-configuration + AOP (`@LeaderElection`) |
| `bluetape4k-leader-micrometer` | Micrometer metrics instrumentation |
| `bluetape4k-leader-ktor` | Ktor 3.x integration — `LeaderElectionPlugin` + `leaderScheduled()` |

## Usage Examples

### Gradle Kotlin DSL

```kotlin
plugins {
    id("io.spring.dependency-management") version "1.1.x"
}

dependencyManagement {
    imports {
        mavenBom("io.github.bluetape4k.leader:bluetape4k-leader-bom:<version>")
    }
}

dependencies {
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-core")
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-redis-lettuce")
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-spring-boot")
}
```

### Plain Gradle

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k.leader:bluetape4k-leader-bom:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-core")
}
```

### Maven

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.bluetape4k.leader</groupId>
            <artifactId>bluetape4k-leader-bom</artifactId>
            <version>${bluetape4k-leader.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

## Configuration Options

The BOM itself has no configuration. For SNAPSHOT builds, add the Sonatype Central Snapshots repository:

```kotlin
repositories {
    mavenCentral()
    maven {
        name = "central-snapshots"
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
    }
}
```

## Dependency

This BOM is automatically aggregated by `bluetape4k-dependencies`. Prefer importing
`io.github.bluetape4k:bluetape4k-dependencies` when consuming multiple bluetape4k ecosystems.
