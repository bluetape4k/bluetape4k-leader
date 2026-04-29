# leader-hazelcast

[한국어](README.ko.md)

Hazelcast-backed leader election — blocking, async, virtual-thread, and coroutine APIs.

---

## Overview

`leader-hazelcast` implements `leader-core` interfaces using Hazelcast `IMap` as a distributed lock store. No CP Subsystem required — lock primitives use `putIfAbsent + TTL` and token-based conditional release.

Lock strategy: `IMap.putIfAbsent(key, token, leaseTimeMs, MILLISECONDS)` for atomic acquire, `IMap.remove(key, token)` for owner-only release. Thread-unbound token model — safe for Virtual Threads and coroutine thread switches.

> **Note:** `leaseTime` must be longer than the expected action duration. TTL expiry automatically releases the lock; no watchdog renewal is performed.
>
> **Note:** Never enable near-cache on the lock map. Stale near-cache reads can cause `isHeldByCurrentInstance()` to misidentify the lock holder.

## Architecture

```mermaid
classDiagram
    class LeaderElection { <<interface>> }
    class LeaderGroupElection { <<interface>> }
    class SuspendLeaderElection { <<interface>> }
    class SuspendLeaderGroupElection { <<interface>> }

    class HazelcastLock {
        +tryLock(waitTime, leaseTime) Boolean
        +isHeldByCurrentInstance() Boolean
        +unlock()
    }
    class HazelcastSuspendLock {
        +tryLock(waitTime, leaseTime) Boolean
        +isHeldByCurrentInstance() Boolean
        +unlock()
    }

    HazelcastLeaderElection ..|> LeaderElection
    HazelcastLeaderGroupElection ..|> LeaderGroupElection
    HazelcastSuspendLeaderElection ..|> SuspendLeaderElection
    HazelcastSuspendLeaderGroupElection ..|> SuspendLeaderGroupElection

    HazelcastLeaderElection --> HazelcastLock
    HazelcastLeaderGroupElection --> HazelcastLock
    HazelcastSuspendLeaderElection --> HazelcastSuspendLock
    HazelcastSuspendLeaderGroupElection --> HazelcastSuspendLock
```

## Implementations

| Class | Interface | Description |
|-------|-----------|-------------|
| `HazelcastLeaderElection` | `LeaderElection` | Blocking + async single-leader |
| `HazelcastLeaderGroupElection` | `LeaderGroupElection` | Blocking + async multi-leader (slot-based) |
| `HazelcastSuspendLeaderElection` | `SuspendLeaderElection` | Coroutine single-leader |
| `HazelcastSuspendLeaderGroupElection` | `SuspendLeaderGroupElection` | Coroutine multi-leader (slot-based) |

## Usage

### Setup

```kotlin
val config = ClientConfig().apply {
    networkConfig.addAddress("localhost:5701")
}
val hazelcast = HazelcastClient.newHazelcastClient(config)
```

### Blocking single-leader

```kotlin
val election = HazelcastLeaderElection(hazelcast)

val result = election.runIfLeader("daily-report") {
    generateReport()
}
// result == generateReport() return value on leader, null on others
```

### Blocking multi-leader group

```kotlin
val options = LeaderGroupElectionOptions(maxLeaders = 3)
val election = HazelcastLeaderGroupElection(hazelcast, options)

val result = election.runIfLeader("parallel-batch") {
    processChunk()
}
// up to 3 nodes run concurrently, rest return null
```

### Async single-leader

```kotlin
val election = HazelcastLeaderElection(hazelcast)

val future: CompletableFuture<Report?> = election.runAsyncIfLeader("daily-report") {
    CompletableFuture.supplyAsync { generateReport() }
}
```

### Coroutine single-leader

```kotlin
val election = HazelcastSuspendLeaderElection(hazelcast)

val result = election.runIfLeader("nightly-sync") {
    delay(100)
    syncData()
}
```

### Coroutine multi-leader group

```kotlin
val options = LeaderGroupElectionOptions(maxLeaders = 2)
val election = HazelcastSuspendLeaderGroupElection(hazelcast, options)

coroutineScope {
    val jobs = (1..5).map {
        async { election.runIfLeader("task-group") { processTask(it) } }
    }
    jobs.awaitAll()  // 2 run concurrently, 3 return null
}
```

### Extension functions

```kotlin
// Blocking
hazelcast.runIfLeader("job") { doWork() }
hazelcast.runIfLeaderGroup("job", options) { doWork() }

// Coroutine
hazelcast.suspendRunIfLeader("job") { doWork() }
hazelcast.suspendRunIfLeaderGroup("job", options) { doWork() }
```

### Custom options

```kotlin
val options = LeaderElectionOptions(
    waitTime = Duration.ofSeconds(3),
    leaseTime = Duration.ofSeconds(60)
)
val election = HazelcastLeaderElection(hazelcast, options)
```

## Lock Internals

`HazelcastLock` uses `IMap` operations for CP-Subsystem-free distributed locking:

```
Acquire: IMap.putIfAbsent(lockKey, token, leaseTimeMs, MILLISECONDS)
         → returns null on success (key was absent), existing token on failure
Release: IMap.remove(lockKey, token)
         → atomic conditional delete — only removes if value matches token
Check:   IMap.get(lockKey) == token
```

Group election simulates a semaphore with N slot keys (`lockName:slot:0` … `lockName:slot:N-1`). Each caller tries slots in sequence; first acquired slot wins.

Lock map names:
- Single-leader: `bluetape4k:leader:locks`
- Group: `bluetape4k:leader:group:locks`

## Dependency

```kotlin
// build.gradle.kts
implementation("io.github.bluetape4k.leader:leader-hazelcast:0.1.0-SNAPSHOT")

// Hazelcast must be on the classpath
implementation("com.hazelcast:hazelcast:5.x.x")
```
