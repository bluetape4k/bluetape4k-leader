# bluetape4k-leader

[![CI](https://github.com/bluetape4k/bluetape4k-leader/actions/workflows/ci.yml/badge.svg)](https://github.com/bluetape4k/bluetape4k-leader/actions/workflows/ci.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![JVM](https://img.shields.io/badge/JVM-21-ED8B00?logo=openjdk)](https://openjdk.org)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

[한국어](README.ko.md)

A standalone Kotlin/JVM library for **distributed leader election**.  
Provides blocking, async, coroutine, and virtual-thread APIs backed by Redis, Exposed, MongoDB, Hazelcast, and ZooKeeper.

---

## Features

- **Null-returning API** — `runIfLeader()` returns `null` when not elected (no exceptions thrown on contention)
- **Multiple execution models** — blocking, `CompletableFuture`, virtual threads, coroutines
- **Multi-leader support** — `LeaderGroupElector` allows N concurrent leaders via distributed semaphore
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
    ExposedCore["leader-exposed-core\n(Stable)"]
    ExposedJdbc["leader-exposed-jdbc\n(Stable)"]
    ExposedR2dbc["leader-exposed-r2dbc\n(Stable)"]
    Mongo["leader-mongodb\n(MongoDB)"]
    SB["leader-spring-boot\n(Boot 4, CTW)"]
    Metrics["leader-micrometer\n(Micrometer metrics)"]
    Ktor["leader-ktor\n(planned)"]
    ZK["leader-zookeeper\n(ZooKeeper)"]

    Lettuce --> Core
    Redisson --> Core
    Hazelcast --> Core
    ExposedCore --> Core
    ExposedJdbc --> ExposedCore
    ExposedR2dbc --> ExposedCore
    Mongo --> Core
    SB --> Core
    Metrics --> Core
    Ktor --> Core
    ZK --> Core
```

## Modules

| Module | Status | Description |
|--------|--------|-------------|
| `leader-core` | Stable | Interfaces + local in-process implementations |
| `leader-redis-lettuce` | Stable | Lettuce-based Redis backend |
| `leader-redis-redisson` | Stable | Redisson-based Redis backend |
| `leader-hazelcast` | Stable | Hazelcast backend (IMap-based, no CP Subsystem) |
| `leader-exposed-core` | Stable | Common Exposed schema (no JDBC/R2DBC driver) |
| `leader-exposed-jdbc` | Stable | Exposed JDBC backend (H2, PostgreSQL, MySQL) |
| `leader-exposed-r2dbc` | Stable | Exposed R2DBC backend (coroutine-native, H2/PostgreSQL/MySQL) |
| `leader-mongodb` | Stable | MongoDB backend (`findOneAndUpdate` + TTL index) |
| `leader-micrometer` | Stable | Micrometer metrics integration (`MicrometerLeaderAopMetricsRecorder`) |
| `leader-spring-boot` | Stable | Spring Boot 4 auto-configuration + AOP (AspectJ CTW, Freefair post-compile weaving) |
| `leader-zookeeper` | Stable | ZooKeeper/Curator backend (`InterProcessMutex` / `InterProcessSemaphoreV2`) |
| `leader-ktor` | Planned | Ktor Plugin DSL + `leaderScheduled()` scheduling helper |

## Quick Start

### Gradle

```kotlin
// Redis (Redisson or Lettuce)
implementation("io.github.bluetape4k.leader:leader-redis-redisson:0.1.0-SNAPSHOT")
// or
implementation("io.github.bluetape4k.leader:leader-redis-lettuce:0.1.0-SNAPSHOT")

// JDBC (H2 / PostgreSQL / MySQL via Exposed)
implementation("io.github.bluetape4k.leader:leader-exposed-jdbc:0.1.0-SNAPSHOT")

// R2DBC coroutine-native (H2 / PostgreSQL / MySQL via Exposed)
implementation("io.github.bluetape4k.leader:leader-exposed-r2dbc:0.1.0-SNAPSHOT")

// ZooKeeper / Apache Curator
implementation("io.github.bluetape4k.leader:leader-zookeeper:0.1.0-SNAPSHOT")
```

### Exposed JDBC (H2 / PostgreSQL / MySQL)

```kotlin
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderElector

val dataSource = HikariDataSource(HikariConfig().apply {
    jdbcUrl = "jdbc:postgresql://localhost:5432/mydb"
    username = "user"
    password = "pass"
})

val election = ExposedJdbcLeaderElector(dataSource)

val result = election.runIfLeader("daily-report-job") {
    generateReport()
}
// result == generateReport() on the leader, null on other nodes
```

Multi-leader group (JDBC):

```kotlin
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderGroupElector
import io.bluetape4k.leader.core.LeaderGroupElectionOptions

val options = LeaderGroupElectionOptions(maxLeaders = 3)
val groupElection = ExposedJdbcLeaderGroupElector(dataSource, options)

val result = groupElection.runIfLeader("parallel-batch") {
    processNextChunk()
}
```

### Blocking (single leader — Redis)

```kotlin
val config = Config().apply { useSingleServer().setAddress("redis://localhost:6379") }
val client = Redisson.create(config)

val election = RedissonLeaderElector(client)

val result = election.runIfLeader("daily-report-job") {
    generateReport()  // runs only on the elected node
}
// result == report on the leader, null on other nodes
```

### Coroutines (suspend)

```kotlin
val election = RedissonSuspendLeaderElector(client)

val result = election.runIfLeader("nightly-cleanup") {
    cleanupExpiredSessions()
}
```

### Multi-leader group (semaphore)

```kotlin
val options = LeaderGroupElectionOptions(maxLeaders = 3)
val election = RedissonLeaderGroupElector(client, options)

// Up to 3 concurrent leaders can run this action simultaneously
val result = election.runIfLeader("parallel-batch") {
    processNextChunk()
}
```

### Custom options

```kotlin
val options = LeaderElectionOptions(
    waitTime = 3.seconds,   // how long to wait for the lock
    leaseTime = 30.seconds, // maximum lock lease
    minLeaseTime = 0.seconds // minimum local hold time; backend TTL delegation follows in #77
)
val election = RedissonLeaderElector(client, options)
```

### Migration notes

- Kotlin API options use `kotlin.time.Duration`. Prefer `5.seconds`, `60.seconds`, `1.minutes` over `java.time.Duration.ofSeconds(...)`.
- Spring Boot YAML still uses Spring's duration binding (`5s`, `60s`, `PT1M`).
- Spring bean names use `LeaderElector` terminology. Prefer `redissonLeaderElectionFactory`, `lettuceSuspendLeaderElectorFactory`, and similar names.

### Local (in-process, no Redis)

```kotlin
// Useful for single-instance or testing scenarios
val election = LocalLeaderElector()
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
| `LeaderElector` | `T?` | Blocking single-leader |
| `AsyncLeaderElector` | `CompletableFuture<T?>` | Async single-leader |
| `VirtualThreadLeaderElector` | `T?` | Virtual thread single-leader |
| `SuspendLeaderElector` | `T?` | Coroutine suspend single-leader |
| `LeaderGroupElector` | `T?` | Blocking multi-leader (semaphore) |
| `SuspendLeaderGroupElector` | `T?` | Coroutine multi-leader (semaphore) |
| `StrategicLeaderElector` | `T?` | Blocking strategic election (candidate registry) |
| `StrategicSuspendLeaderElector` | `T?` | Coroutine strategic election (candidate registry) |

`runIfLeader(lockName, action)` — returns `action()` result on success, `null` if not elected.

### Distinguishing elected vs skipped: `LeaderRunResult`

`runIfLeader()` returns `null` for two distinct cases: (a) lock not acquired and (b) `action()` legitimately returning `null`. Use `runIfLeaderResult` (available on both `LeaderElector` and `LeaderGroupElector`) when you need to tell them apart — for example, in metrics or conditional post-processing:

```kotlin
when (val r = election.runIfLeaderResult("daily-job") { compute() }) {
    is LeaderRunResult.Elected -> println("elected, result=${r.value}")
    is LeaderRunResult.Skipped -> println("skipped — lock not acquired")
}
```

`LeaderRunResult` is a sealed interface with two variants: `Elected<T>(value: T?)` and `Skipped`. Available on synchronous `LeaderElector` and `LeaderGroupElector` only (async/suspend equivalents planned for a future release).

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
val election = LettuceStrategicLeaderElector(connection, nodeId = "node-1")

// register this node
election.registerCandidate("batch-job", CandidateInfo("node-1"), ttl = 5.minutes)

// elect and run
val result = election.runIfLeader("batch-job", FifoElectionStrategy) {
    processBatch()
}
// result: processBatch() on the winning node, null on others
```

### Example — Success-rate scoring (coroutine, Redisson)

```kotlin
val election = RedissonStrategicSuspendLeaderElector(redissonClient, nodeId = "node-1")
election.registerCandidate("ml-job", CandidateInfo("node-1"), ttl = 10.minutes)

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

## Spring Boot AOP

`leader-spring-boot` provides `@LeaderElection` and `@LeaderGroupElection` annotations backed by AspectJ CTW (Freefair post-compile weaving).

```kotlin
@Service
class ReportService {
    @LeaderElection(name = "daily-report-job")
    fun generateReport(): String { /* runs only on elected node */ }

    // Fail-open: run the body even when lock is not acquired or backend is unavailable
    @LeaderElection(name = "nightly-cleanup", failureMode = LeaderAspectFailureMode.FAIL_OPEN_RUN)
    fun cleanup(): String { /* always runs, lock is best-effort */ }
}
```

### `failureMode`

Controls what happens when the lock is **not** acquired (contention or backend error):

| Value | Behaviour |
|-------|-----------|
| `RETHROW` (default) | Throw `LeaderElectionException` wrapping the backend error |
| `SKIP` | Return `null` — body is not executed |
| `FAIL_OPEN_RUN` | Run the method body anyway and return its result |

`FAIL_OPEN_RUN` is designed for jobs where skipping is worse than running without the distributed lock guarantee (e.g., best-effort idempotent tasks). Metrics record `SkipReason.FAIL_OPEN_FORCED` so dashboards can track lock-free executions separately.

### Global default via properties

```yaml
bluetape4k:
  leader:
    aop:
      failure-mode: FAIL_OPEN_RUN   # RETHROW | SKIP | FAIL_OPEN_RUN
```

---

## Micrometer Metrics

When using Spring Boot AOP (`@LeaderElection`), add `leader-micrometer` to expose Prometheus/Datadog metrics automatically.

### Dependency

```kotlin
implementation("io.github.bluetape4k.leader:leader-spring-boot:0.1.0-SNAPSHOT")
implementation("io.github.bluetape4k.leader:leader-micrometer:0.1.0-SNAPSHOT")
```

`MicrometerLeaderAopMetricsRecorder` is auto-registered when a `MeterRegistry` bean is present. Disable with:

```yaml
bluetape4k:
  leader:
    aop:
      metrics:
        enabled: false
```

### Meter Catalog

| Meter name | Type | Description |
|------------|------|-------------|
| `leader.aop.attempts` | Counter | Lock acquisition attempts per `lock.name` |
| `leader.aop.acquired` | Counter | Successful leader elections |
| `leader.aop.lock.not.acquired` | Counter | Skipped executions; tagged with `reason` (`CONTENTION` / `BACKEND_ERROR`) |
| `leader.aop.execution.duration` | Timer | Elapsed time of the leader action |
| `leader.aop.task.failed` | Counter | Action body exceptions; tagged with `exception` class name |
| `leader.aop.active` | Gauge | Currently running leader actions (JVM-local) |
| `shedlock.leader.acquired` | Counter | Decorator-based successful leader executions |
| `shedlock.leader.not_acquired` | Counter | Decorator-based skipped executions |
| `shedlock.leader.duration` | Timer | Decorator-based leader action duration |
| `shedlock.leader.active` | Gauge | Decorator-based currently running leader actions (JVM-local) |

All meters are tagged with `lock.name`. Micrometer's `NamingConvention` converts names per backend (e.g., `leader_aop_attempts_total` for Prometheus).

> **Multi-instance note:** `leader.aop.active` is JVM-local. Use `max by (lock_name) (leader_aop_active)` in Prometheus — not `sum` — to avoid counting each node's gauge separately.

### Decorator metrics

Use the decorator wrappers when you call leader electors directly instead of Spring AOP:

```kotlin
val election = InstrumentedLeaderElector(delegate, registry)
val result = election.runIfLeader("daily-report-job") {
    generateReport()
}

val groupElection = InstrumentedLeaderGroupElector(groupDelegate, registry)
groupElection.runIfLeader("batch-shard") {
    processShard()
}

val suspendElection = InstrumentedSuspendLeaderElector(suspendDelegate, registry)
suspendElection.runIfLeader("sync-job") {
    syncData()
}
```

Pass `lockName = "static-job"` to any wrapper to use a fixed `lock.name` tag; omit it to use the per-call lock name.

### Pre-registration (optional)

Pre-register static lock names at startup so metrics appear in dashboards even before the first execution:

```kotlin
@Component
class MetricsPreRegistrar(private val recorder: MicrometerLeaderAopMetricsRecorder) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        recorder.registerMetricsFor("daily-report-job", "nightly-cleanup")
    }
}
```

### Health Indicator

When `spring-boot-actuator` is on the classpath, a `leaderMetricsHealthIndicator` bean is registered automatically:

```
GET /actuator/health/leaderMetricsHealthIndicator
{
  "status": "UP",
  "details": {
    "active": 0,
    "trackedLocks": 2
  }
}
```

### Custom recorder

Provide your own `LeaderAopMetricsRecorder` bean to replace the default Micrometer implementation:

```kotlin
@Bean
fun myRecorder(): LeaderAopMetricsRecorder = MyCustomRecorder()
```

---

## Comparison with ShedLock

| Feature | bluetape4k-leader | ShedLock |
|---------|-------------------|----------|
| Skip on contention | `null` return | annotation-based skip |
| Coroutine support | Native | No |
| Virtual thread support | Yes | No |
| Multi-leader (group) | `LeaderGroupElector` | No |
| Redis (Lettuce) | Yes | Yes |
| Redis (Redisson) | Yes | Yes |
| Spring integration | Yes (Boot 4 + AspectJ CTW) | Yes (core feature) |
| JDBC/SQL | Yes (Exposed JDBC) | Yes |
| MongoDB | Yes | Yes |
| Hazelcast | Yes | Yes |
| ZooKeeper | Yes | No |

## Requirements

- JVM 21+
- Kotlin 2.3+

## License

Apache License 2.0 — see [LICENSE](LICENSE).
