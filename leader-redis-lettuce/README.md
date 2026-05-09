# leader-redis-lettuce

[한국어](README.ko.md)

Redis-backed leader election using [Lettuce](https://lettuce.io/) — blocking and coroutine APIs.

---

## Overview

`leader-redis-lettuce` implements `leader-core` interfaces using Lettuce's reactive Redis client. Lock primitives (`LettuceLock`, `LettuceSemaphore`) are ported directly into this module — no runtime dependency on `bluetape4k-lettuce`.

Lock strategy: Redis `SET key value NX PX ttl` (atomic compare-and-set). Renewal is not automatic; the caller must ensure `leaseTime` is longer than the expected action duration.

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

    class LettuceLock {
        +tryLock(key, value, ttl) Boolean
        +unlock(key, value)
    }
    class LettuceSemaphore {
        +acquire(key, permits, ttl) Boolean
        +release(key, permits)
    }
    class LettuceSuspendLock {
        +tryLock(key, value, ttl) Boolean
        +unlock(key, value)
    }
    class LettuceSuspendSemaphore {
        +acquire(key, permits, ttl) Boolean
        +release(key, permits)
    }

    LettuceLeaderElector ..|> LeaderElector
    LettuceLeaderGroupElector ..|> LeaderGroupElector
    LettuceSuspendLeaderElector ..|> SuspendLeaderElector
    LettuceSuspendLeaderGroupElector ..|> SuspendLeaderGroupElector

    LettuceLeaderElector --> LettuceLock
    LettuceLeaderGroupElector --> LettuceSemaphore
    LettuceSuspendLeaderElector --> LettuceSuspendLock
    LettuceSuspendLeaderGroupElector --> LettuceSuspendSemaphore
```

## Implementations

| Class | Interface | Description |
|-------|-----------|-------------|
| `LettuceLeaderElector` | `LeaderElector` | Blocking single-leader via `LettuceLock` |
| `LettuceLeaderGroupElector` | `LeaderGroupElector` | Blocking multi-leader via `LettuceSemaphore` |
| `LettuceSuspendLeaderElector` | `SuspendLeaderElector` | Coroutine single-leader via `LettuceSuspendLock` |
| `LettuceSuspendLeaderGroupElector` | `SuspendLeaderGroupElector` | Coroutine multi-leader via `LettuceSuspendSemaphore` |
| `LettuceSuspendLeaderElectorFactory` | `SuspendLeaderElectorFactory` | Factory: creates `LettuceSuspendLeaderElector` per call |
| `LettuceSuspendLeaderGroupElectorFactory` | `SuspendLeaderGroupElectorFactory` | Factory: creates `LettuceSuspendLeaderGroupElector` per call |

## Usage

### Setup

```kotlin
val redisClient = RedisClient.create("redis://localhost:6379")
val connection = redisClient.connect()
```

### Blocking single-leader

```kotlin
val election = LettuceLeaderElector(connection)

val result = election.runIfLeader("daily-report") {
    generateReport()
}
// result == report on leader node, null on others
```

### Blocking multi-leader group

```kotlin
val options = LeaderGroupElectionOptions(maxLeaders = 3)
val election = LettuceLeaderGroupElector(connection, options)

val result = election.runIfLeader("parallel-batch") {
    processChunk()
}
```

### Coroutine suspend single-leader

```kotlin
val election = LettuceSuspendLeaderElector(connection)

coroutineScope {
    val result = election.runIfLeader("nightly-sync") {
        syncData()
    }
}
```

### Coroutine multi-leader group

```kotlin
val options = LeaderGroupElectionOptions(maxLeaders = 2)
val election = LettuceSuspendLeaderGroupElector(connection, options)

coroutineScope {
    val jobs = (1..5).map {
        async {
            election.runIfLeader("task-group") {
                processTask(it)
            }
        }
    }
    jobs.awaitAll()  // 2 run concurrently, 3 get null
}
```

### Custom options

```kotlin
val options = LeaderElectionOptions(
    waitTime = 3.seconds,
    leaseTime = 30.seconds
)
val election = LettuceLeaderElector(connection, options)
```

### Using factories

```kotlin
val factory: SuspendLeaderElectorFactory = LettuceSuspendLeaderElectorFactory(connection)

coroutineScope {
    val elector = factory.create(LeaderElectionOptions.Default)
    val result = elector.runIfLeader("daily-job") { doWork() }
}
```

```kotlin
val groupFactory: SuspendLeaderGroupElectorFactory = LettuceSuspendLeaderGroupElectorFactory(connection)

coroutineScope {
    val elector = groupFactory.create(LeaderGroupElectionOptions(maxLeaders = 3))
    val result = elector.runIfLeader("parallel-job") { processChunk() }
}
```

## Lock Internals

`LettuceLock` uses a Lua script to ensure atomic unlock (only the lock owner can release):

```lua
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0
end
```

`LettuceSemaphore` maintains a Redis list of permit tokens. Acquire appends a token; release removes one.

## Dependency

```kotlin
// build.gradle.kts
implementation("io.github.bluetape4k.leader:leader-redis-lettuce:0.1.0-SNAPSHOT")

// Lettuce must be on the classpath
implementation("io.lettuce:lettuce-core:6.x.x")
```
