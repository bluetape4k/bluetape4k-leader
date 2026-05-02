# bluetape4k-leader

[한국어](README.ko.md)

A standalone Kotlin/JVM library for **distributed leader election**.  
Provides blocking, async, coroutine, and virtual-thread APIs backed by Redis (Lettuce, Redisson), with more backends planned.

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3-purple.svg)](https://kotlinlang.org/)
[![JVM](https://img.shields.io/badge/JVM-21-green.svg)](https://openjdk.org/)

---

## Features

- **Null-returning API** — `runIfLeader()` returns `null` when not elected (no exceptions thrown on contention)
- **Multiple execution models** — blocking, `CompletableFuture`, virtual threads, coroutines
- **Multi-leader support** — `LeaderGroupElection` allows N concurrent leaders via distributed semaphore
- **Strategic election** — pluggable candidate-registry + election strategy (FIFO, scored, weighted); no distributed lock required
- **Self-contained Redis test infrastructure** — Testcontainers, no external test-util dependencies
- **ShedLock-compatible skip semantics** — action is simply skipped if the lock cannot be acquired

## Architecture

```mermaid
graph TD
    Core["leader-core\n(Interfaces + Local impls)"]
    Lettuce["leader-redis-lettuce\n(Lettuce Redis)"]
    Redisson["leader-redis-redisson\n(Redisson Redis)"]
    Hazelcast["leader-hazelcast\n(Hazelcast)"]
    ExposedCore["leader-exposed-core\n(planned)"]
    ExposedJdbc["leader-exposed-jdbc\n(planned)"]
    ExposedR2dbc["leader-exposed-r2dbc\n(planned)"]
    Mongo["leader-mongodb\n(MongoDB)"]
    SBCommon["leader-spring-boot-common\n(Boot version-independent)"]
    SB3["leader-spring-boot3\n(planned)"]
    SB4["leader-spring-boot4\n(planned)"]
    Metrics["leader-micrometer\n(planned)"]

    Lettuce --> Core
    Redisson --> Core
    Hazelcast --> Core
    ExposedCore --> Core
    ExposedJdbc --> ExposedCore
    ExposedR2dbc --> ExposedCore
    Mongo --> Core
    SBCommon --> Core
    SB3 --> SBCommon
    SB4 --> SBCommon
    Metrics --> Core
```

## Modules

| Module | Status | Description |
|--------|--------|-------------|
| `leader-core` | Stable | Interfaces + local in-process implementations |
| `leader-redis-lettuce` | Stable | Lettuce-based Redis backend |
| `leader-redis-redisson` | Stable | Redisson-based Redis backend |
| `leader-hazelcast` | Stable | Hazelcast backend (IMap-based, no CP Subsystem) |
| `leader-exposed-core` | Planned | Common Exposed schema (no JDBC/R2DBC driver) |
| `leader-exposed-jdbc` | Planned | Exposed JDBC backend |
| `leader-exposed-r2dbc` | Planned | Exposed R2DBC backend |
| `leader-mongodb` | Stable | MongoDB backend (`findOneAndUpdate` + TTL index) |
| `leader-micrometer` | Planned | Micrometer metrics integration |
| `leader-spring-boot-common` | Stable | Boot version-independent properties + auto-config support |
| `leader-spring-boot3` | Planned | Spring Boot 3 auto-configuration |
| `leader-spring-boot4` | Planned | Spring Boot 4 auto-configuration |

## Quick Start

### Gradle

```kotlin
implementation("io.github.bluetape4k.leader:leader-redis-redisson:0.1.0-SNAPSHOT")
// or
implementation("io.github.bluetape4k.leader:leader-redis-lettuce:0.1.0-SNAPSHOT")
```

### Blocking (single leader)

```kotlin
val config = Config().apply { useSingleServer().setAddress("redis://localhost:6379") }
val client = Redisson.create(config)

val election = RedissonLeaderElection(client)

val result = election.runIfLeader("daily-report-job") {
    generateReport()  // runs only on the elected node
}
// result == report on the leader, null on other nodes
```

### Coroutines (suspend)

```kotlin
val election = RedissonSuspendLeaderElection(client)

val result = election.runIfLeader("nightly-cleanup") {
    cleanupExpiredSessions()
}
```

### Multi-leader group (semaphore)

```kotlin
val options = LeaderGroupElectionOptions(maxLeaders = 3)
val election = RedissonLeaderGroupElection(client, options)

// Up to 3 concurrent leaders can run this action simultaneously
val result = election.runIfLeader("parallel-batch") {
    processNextChunk()
}
```

### Custom options

```kotlin
val options = LeaderElectionOptions(
    waitTime = Duration.ofSeconds(3),   // how long to wait for the lock
    leaseTime = Duration.ofSeconds(30)  // how long to hold the lock
)
val election = RedissonLeaderElection(client, options)
```

### Local (in-process, no Redis)

```kotlin
// Useful for single-instance or testing scenarios
val election = LocalLeaderElection()
val result = election.runIfLeader("job") { "done" }
```

## How `runIfLeader` Works

Multiple nodes call `runIfLeader` concurrently — only one acquires the lock and runs the action; the rest return `null`.

```mermaid
sequenceDiagram
    participant NodeA
    participant NodeB
    participant LockStore

    par NodeA attempts
        NodeA->>LockStore: tryLock("job", waitTime, leaseTime)
    and NodeB attempts
        NodeB->>LockStore: tryLock("job", waitTime, leaseTime)
    end

    LockStore-->>NodeA: acquired (true)
    LockStore-->>NodeB: not acquired → wait/retry until timeout

    NodeA->>NodeA: action()
    NodeA->>LockStore: unlock("job")
    NodeA-->>NodeA: return action() result

    LockStore-->>NodeB: timeout (false)
    NodeB-->>NodeB: return null
```

### Multi-leader group: slot-based semaphore

```mermaid
sequenceDiagram
    participant Caller
    participant GroupElection
    participant LockStore

    Caller->>GroupElection: runIfLeader(lockName, action)
    loop slot = 0..maxLeaders-1
        GroupElection->>LockStore: tryLock(lockName:slot:i, ...)
        alt slot acquired
            LockStore-->>GroupElection: true
            GroupElection->>Caller: action()
            Caller-->>GroupElection: result
            GroupElection->>LockStore: unlock(lockName:slot:i)
            GroupElection-->>Caller: result
        else slot busy
            LockStore-->>GroupElection: false
            Note over GroupElection: try next slot
        end
    end
    Note over GroupElection: all slots busy → return null
```

## API Overview

### Core interfaces

| Interface | Returns | Description |
|-----------|---------|-------------|
| `LeaderElection` | `T?` | Blocking single-leader |
| `AsyncLeaderElection` | `CompletableFuture<T?>` | Async single-leader |
| `VirtualThreadLeaderElection` | `T?` | Virtual thread single-leader |
| `SuspendLeaderElection` | `T?` | Coroutine suspend single-leader |
| `LeaderGroupElection` | `T?` | Blocking multi-leader (semaphore) |
| `SuspendLeaderGroupElection` | `T?` | Coroutine multi-leader (semaphore) |
| `StrategicLeaderElection` | `T?` | Blocking strategic election (candidate registry) |
| `StrategicSuspendLeaderElection` | `T?` | Coroutine strategic election (candidate registry) |

`runIfLeader(lockName, action)` — returns `action()` result on success, `null` if not elected.

### Options

```kotlin
LeaderElectionOptions(
    waitTime: Duration = 5.seconds,
    leaseTime: Duration = 60.seconds
)

LeaderGroupElectionOptions(
    maxLeaders: Int = 2,
    waitTime: Duration = 5.seconds,
    leaseTime: Duration = 60.seconds
)
```

## Strategic Election

Strategic election replaces the distributed-lock acquisition race with a **candidate registry + pluggable strategy**. Each node registers itself as a candidate; on each `runIfLeader` call, all candidates are loaded and a strategy deterministically selects the winner. No lock is held — only the winning node executes the action.

### CandidateInfo

```kotlin
CandidateInfo(
    nodeId: String,                      // unique node identifier
    registeredAt: Instant,               // registration timestamp (for FIFO)
    lastCompletionTime: Instant? = null, // for idle-time scoring
    successCount: Long = 0L,             // auto-incremented on success
    failureCount: Long = 0L,             // auto-incremented on failure
    metadata: Map<String, String> = emptyMap(),
)
```

### Built-in strategies

| Strategy | Description |
|----------|-------------|
| `FifoElectionStrategy` | Earliest `registeredAt` wins; ties broken by `nodeId` lexicographic order |
| `RandomElectionStrategy` | Random pick each round |
| `ScoredElectionStrategy(scorer)` | Highest-score candidate wins |

### Built-in scorers

| Scorer | Description |
|--------|-------------|
| `SuccessRateScorer` | `successCount / (successCount + failureCount)` |
| `IdleTimeScorer` | Longer idle time → higher score (load balancing) |
| `RecentSuccessScorer` | Recency-weighted success rate |
| `WeightedScorer(vararg pairs)` | Linear combination of multiple scorers |

### Example — FIFO (Lettuce)

```kotlin
val election = LettuceStrategicLeaderElection(connection, nodeId = "node-1")

// register this node
election.registerCandidate("batch-job", CandidateInfo("node-1"), ttl = Duration.ofMinutes(5))

// elect and run
val result = election.runIfLeader("batch-job", FifoElectionStrategy) {
    processBatch()
}
// result: processBatch() on the winning node, null on others
```

### Example — Success-rate scoring (coroutine, Redisson)

```kotlin
val election = RedissonStrategicSuspendLeaderElection(redissonClient, nodeId = "node-1")
election.registerCandidate("ml-job", CandidateInfo("node-1"), ttl = Duration.ofMinutes(10))

val strategy = ScoredElectionStrategy(SuccessRateScorer)
val result = election.runIfLeader("ml-job", strategy) {
    runInference()
}
```

### Example — Weighted composite scorer

```kotlin
val scorer = WeightedScorer(
    SuccessRateScorer to 0.7,
    IdleTimeScorer    to 0.3,
)
val result = election.runIfLeader("job", ScoredElectionStrategy(scorer)) { doWork() }
```

### Strategic election vs lock-based election

| Aspect | Lock-based | Strategic |
|--------|-----------|-----------|
| Winner selection | First to acquire lock | Deterministic strategy |
| Candidate history | None | `successCount`, `failureCount`, `idleDuration` |
| TTL per candidate | No (lock-level TTL) | Yes (per-node expiry) |
| Custom scorer | No | Yes (`CandidateScorer`) |
| Network RTT | 1 (tryLock) | 2 (list + elect) |

## Comparison with ShedLock

| Feature | bluetape4k-leader | ShedLock |
|---------|-------------------|----------|
| Skip on contention | `null` return | annotation-based skip |
| Coroutine support | Native | No |
| Virtual thread support | Yes | No |
| Multi-leader (group) | `LeaderGroupElection` | No |
| Redis (Lettuce) | Yes | Yes |
| Redis (Redisson) | Yes | Yes |
| Spring integration | Planned | Yes (core feature) |
| JDBC/SQL | Planned | Yes |
| MongoDB | Planned | Yes |
| Hazelcast | Yes | Yes |

## Requirements

- JVM 21+
- Kotlin 2.3+

## License

Apache License 2.0 — see [LICENSE](LICENSE).
