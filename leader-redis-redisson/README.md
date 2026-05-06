# leader-redis-redisson

[한국어](README.ko.md)

Redis-backed leader election using [Redisson](https://redisson.org/) — blocking and coroutine APIs.

---

## Overview

`leader-redis-redisson` implements `leader-core` interfaces using Redisson's `RLock` and `RSemaphore`. It supports blocking, async, coroutine, and virtual-thread execution models.

The coroutine implementation uses a PID-seeded mini-Snowflake ID generator to produce unique per-coroutine lock IDs without Redis round-trips, ensuring safety in HA (multi-JVM) deployments.

## Architecture

```mermaid
classDiagram
    class LeaderElector {
        <<interface>>
    }
    class LeaderGroupElector {
        <<interface>>
    }
    class SuspendLeaderElector {
        <<interface>>
    }
    class SuspendLeaderGroupElector {
        <<interface>>
    }

    class RedissonLeaderElector {
        -redissonClient RedissonClient
        -options LeaderElectionOptions
        +runIfLeader(lockName, action) T?
    }
    class RedissonLeaderGroupElector {
        -redissonClient RedissonClient
        -options LeaderGroupElectionOptions
        +runIfLeader(lockName, action) T?
    }
    class RedissonSuspendLeaderElector {
        -redissonClient RedissonClient
        -options LeaderElectionOptions
        +runIfLeader(lockName, action) T?
    }
    class RedissonSuspendLeaderGroupElector {
        -redissonClient RedissonClient
        -options LeaderGroupElectionOptions
        +runIfLeader(lockName, action) T?
    }

    RedissonLeaderElector ..|> LeaderElector
    RedissonLeaderGroupElector ..|> LeaderGroupElector
    RedissonSuspendLeaderElector ..|> SuspendLeaderElector
    RedissonSuspendLeaderGroupElector ..|> SuspendLeaderGroupElector
```

## Implementations

| Class | Interface | Description |
|-------|-----------|-------------|
| `RedissonLeaderElector` | `LeaderElector` | Blocking via `RLock.tryLock()` |
| `RedissonLeaderGroupElector` | `LeaderGroupElector` | Blocking multi-leader via `RSemaphore` |
| `RedissonSuspendLeaderElector` | `SuspendLeaderElector` | Coroutine, PID-seeded Snowflake lock ID |
| `RedissonSuspendLeaderGroupElector` | `SuspendLeaderGroupElector` | Coroutine multi-leader via `RSemaphoreAsync` |
| `RedissonSuspendLeaderElectorFactory` | `SuspendLeaderElectorFactory` | Factory: creates `RedissonSuspendLeaderElector` per call |
| `RedissonSuspendLeaderGroupElectorFactory` | `SuspendLeaderGroupElectorFactory` | Factory: creates `RedissonSuspendLeaderGroupElector` per call |

## Coroutine Lock ID Design

Redisson treats the lock ID (thread ID) as the "owner" identifier — the same ID means "I own this lock" and enables reentrancy. In coroutine environments, multiple coroutines run on the same thread, so a thread-based ID would cause false reentrancy.

`RedissonSuspendLeaderElector` generates a unique lock ID per `runIfLeader` call using a mini-Snowflake:

```
timestamp(42 bits) | pid%(2^10)(10 bits) | seq(12 bits)
```

- `pid % 1024` as machine ID — reasonably collision-resistant across JVM processes in HA
- Per-instance `AtomicLong` sequence counter (12 bits, wraps after 4096)
- Zero Redis I/O — pure in-memory computation

## Usage

### Setup

```kotlin
val config = Config().apply {
    useSingleServer()
        .setAddress("redis://localhost:6379")
        .setConnectionPoolSize(8)
        .setConnectionMinimumIdleSize(2)
}
val client = Redisson.create(config)
```

### Blocking single-leader

```kotlin
val election = RedissonLeaderElector(client)

val result = election.runIfLeader("daily-report") {
    generateReport()
}
// result == report on leader, null on others
```

### Blocking multi-leader group

```kotlin
val options = LeaderGroupElectionOptions(maxLeaders = 3)
val election = RedissonLeaderGroupElector(client, options)

val result = election.runIfLeader("parallel-batch") {
    processChunk()
}

println(election.activeCount("parallel-batch"))    // 0–3
println(election.availableSlots("parallel-batch")) // remaining slots
```

### Coroutine suspend single-leader

```kotlin
val election = RedissonSuspendLeaderElector(client)

val result = election.runIfLeader("nightly-sync") {
    syncData()
}
```

### Coroutine multi-leader group

```kotlin
val options = LeaderGroupElectionOptions(maxLeaders = 2)
val election = RedissonSuspendLeaderGroupElector(client, options)

coroutineScope {
    val jobs = (1..5).map {
        async {
            election.runIfLeader("worker-pool") {
                processTask(it)
            }
        }
    }
    jobs.awaitAll()  // 2 run concurrently, 3 return null
}
```

### Custom options

```kotlin
val options = LeaderElectionOptions(
    waitTime = Duration.ofSeconds(3),
    leaseTime = Duration.ofSeconds(30)
)
val election = RedissonLeaderElector(client, options)
```

### Using `invoke` factory

```kotlin
val election = RedissonSuspendLeaderElector(client, LeaderElectionOptions.Default)
```

### Using SPI factories

```kotlin
val factory: SuspendLeaderElectorFactory = RedissonSuspendLeaderElectorFactory(client)

coroutineScope {
    val elector = factory.create(LeaderElectionOptions.Default)
    val result = elector.runIfLeader("daily-job") { doWork() }
}
```

```kotlin
val groupFactory: SuspendLeaderGroupElectorFactory = RedissonSuspendLeaderGroupElectorFactory(client)

coroutineScope {
    val elector = groupFactory.create(LeaderGroupElectionOptions(maxLeaders = 3))
    val result = elector.runIfLeader("parallel-job") { processChunk() }
}
```

## Test Infrastructure

Tests use `bluetape4k-testcontainers` `RedisServer.Launcher.redis`, a JVM-wide singleton Redis container:

```kotlin
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractRedissonLeaderTest {
    companion object : KLogging() {
        val redis = RedisServer.Launcher.redis
        val redisUrl: String get() = redis.url
    }
}
```

## Dependency

```kotlin
// build.gradle.kts
implementation("io.github.bluetape4k.leader:leader-redis-redisson:0.1.0-SNAPSHOT")

// Redisson must be on the classpath
implementation("org.redisson:redisson:3.x.x")
```
