# bluetape4k-leader

English | [한국어](README.ko.md)

[![MvnRepository](https://badges.mvnrepository.com/badge/io.github.bluetape4k.leader/bluetape4k-leader-bom/badge.svg?label=MvnRepository)](https://mvnrepository.com/artifact/io.github.bluetape4k.leader/bluetape4k-leader-bom)
[![CI](https://github.com/bluetape4k/bluetape4k-leader/actions/workflows/ci.yml/badge.svg)](https://github.com/bluetape4k/bluetape4k-leader/actions/workflows/ci.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![JVM](https://img.shields.io/badge/JVM-25-ED8B00?logo=openjdk)](https://openjdk.org)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Current stable version: `1.0.0`

Current development line: `1.1.0`

![Bluetape4k leader election workbench](./docs/assets/leader-election-workbench.png)

A standalone Kotlin/JVM library for **distributed leader election**.  
Provides blocking, async, coroutine, and virtual-thread APIs backed by Redis, Exposed, MongoDB, DynamoDB, etcd, Consul, Kubernetes, Hazelcast, and ZooKeeper.
Spring Boot 4 auto-configuration and Ktor 3.x integration are first-class.

---

## Features

- **Null-returning API** — `runIfLeader()` returns `null` when not elected (no exceptions thrown on contention)
- **Multiple execution models** — blocking, `CompletableFuture`, virtual threads, coroutines
- **Multi-leader support** — `LeaderGroupElector` allows N concurrent leaders via distributed semaphore
- **Strategic election** — pluggable candidate-registry + election strategy (FIFO, scored, weighted); no distributed lock required
- **Strategic group election** — `GroupElectionStrategy` selects a deterministic top-N candidate list for blocking and coroutine APIs
- **Self-contained Redis test infrastructure** — Testcontainers, no external test-util dependencies
- **ShedLock-compatible skip semantics** — action is simply skipped if the lock cannot be acquired

<!-- README_VISUAL_OVERVIEW:START -->
## Overview Diagram

![Bluetape4k Leader overview diagram](docs/images/readme-diagrams/root-readme-overview-01.png)

## Module Composition Chart

![Bluetape4k Leader module composition chart](docs/images/readme-charts/root-readme-module-chart-01.png)
<!-- README_VISUAL_OVERVIEW:END -->

## Manual

The [Leader 1.0.0 manual](https://bluetape4k.github.io/manual/bluetape4k-leader/1.0/) is the source of truth for release behavior. It covers model and backend selection, result and cancellation semantics, Spring Boot and Ktor integration, operations, and a progressive path through all runnable examples. README files remain concise entry points; detailed guidance belongs in the central manual.

## Development status

`1.0.0` is the latest stable release, while `develop` tracks the
`1.1.0-SNAPSHOT` build on the `1.1.0+` development line. [`WIP.md`](./WIP.md) records the dated project snapshot and
release boundary; [`CHANGELOG.md`](./CHANGELOG.md) lists released and upcoming
changes. The versioned manual is pinned to the exact `1.0.0` release commit.

## Benchmarks

The non-published [`benchmark`](./benchmark) module publishes comparable
`kotlinx-benchmark` suites for leader election backends. The JVM runner is JMH;
results are intended for same-machine before/after comparison, not release-grade
performance claims.

![Leader benchmark distributed throughput](docs/images/readme-charts/leader-benchmark-distributed-throughput-chart-01.png)

| Comparison | Primary signal |
|---|---|
| Blocking distributed backends | Hazelcast, Lettuce, and Redisson are tightly grouped at the top in the 2026-05-29 run. |
| Suspend distributed backends | Lettuce, Redisson, and Hazelcast remain the leading group; RDB rows are much slower in this single-container run. |
| Local and H2 rows | Kept out of the distributed backend chart because they measure in-process or local SQL/R2DBC overhead, not distributed backend cost. |

Full tables, latency chart, run command, and caveats are in the
[`benchmark` README](./benchmark/README.md) and the
[`2026-05-29 raw benchmark JSON`](./docs/benchmarks/2026-05-29-issue-405-rdb-backend-throughput.json).

## Architecture

![Leader election runtime map](docs/images/readme-diagrams/bluetape4k-leader-architecture-01.png)

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
| `leader-dynamodb` | Preview | AWS DynamoDB backend (conditional writes + logical TTL) |
| `leader-etcd` | Preview | etcd v3 backend (jetcd Lock service + leases, single/group leader) |
| `leader-consul` | Preview | Consul Session + KV backend (single/group leader, Spring Boot auto-config) |
| `leader-k8s` | Preview | Kubernetes Lease backend (`coordination.k8s.io/v1`) |
| `leader-micrometer` | Stable | Micrometer metrics integration (`MicrometerLeaderAopMetricsRecorder`) |
| `leader-spring-boot` | Stable | Spring Boot 4 auto-configuration + AOP (AspectJ CTW, Freefair post-compile weaving) |
| `leader-zookeeper` | Stable | ZooKeeper/Curator backend (`InterProcessMutex` / `InterProcessSemaphoreV2`) |
| `leader-ktor` | Stable | Ktor 3.x integration — `LeaderElectionPlugin` + `leaderScheduled()` |

## Backend capability matrix

`N` is a backend-native execution path. `B` is a bridge that runs blocking work through an `Executor`, `Dispatchers.IO`, or a virtual-thread wrapper; it does not promise non-blocking backend I/O. `—` means that execution API is not provided. `S` and `G` mean single-leader and group-leader support, respectively.

| Backend | Module | S-Block | S-Async | S-Suspend | S-Virtual | G-Block | G-Async | G-Suspend | G-Virtual | `autoExtend` | State | Audit ID |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
<!-- LEADER_CAPABILITY_MATRIX:START -->
| Local | `bluetape4k-leader-core` | N | B | N | B | N | B | N | B | S | S/G | S |
| Lettuce | `bluetape4k-leader-redis-lettuce` | N | N | N | — | N | N | N | — | S | G | — |
| Redisson | `bluetape4k-leader-redis-redisson` | N | N | N | — | N | N | N | — | S | G | — |
| Exposed JDBC | `bluetape4k-leader-exposed-jdbc` | N | B | — | B | N | B | — | — | S | G | — |
| Exposed R2DBC | `bluetape4k-leader-exposed-r2dbc` | — | — | N | — | — | — | N | — | S | G | — |
| MongoDB | `bluetape4k-leader-mongodb` | N | N | N | — | N | N | N | — | S | G | — |
| Hazelcast | `bluetape4k-leader-hazelcast` | N | B | B | — | N | B | B | — | S | G | — |
| etcd | `bluetape4k-leader-etcd` | N | B | N | B | N | B | N | — | S | G | — |
| Consul | `bluetape4k-leader-consul` | N | B | N | — | N | B | N | — | S | S/G | S |
| DynamoDB | `bluetape4k-leader-dynamodb` | N | B | N | B | N | B | N | B | S | S/G | S |
| Kubernetes | `bluetape4k-leader-k8s` | N | B | B | — | N | B | B | — | S | S/G | S |
| ZooKeeper | `bluetape4k-leader-zookeeper` | N | B | B | — | N | B | B | — | — | G | — |
<!-- LEADER_CAPABILITY_MATRIX:END -->

This matrix is validated against the current source tree. The versioned manual remains pinned to its release commit, so use it for stable-release behavior and this matrix for development-line capability selection.

`State` lists backends that override the single (`S`) or group (`G`) state snapshot instead of returning the core empty single-state default. `Audit ID` means that single-leader state preserves the caller-facing `LeaderSlot.leaderId` (`supportsAuditLeaderState = true`).

`autoExtend` is opt-in and single-leader only. Local, Redis, Exposed, MongoDB, Hazelcast, etcd, Consul, DynamoDB, and Kubernetes renew their own TTL, lease, or session through the shared extender contract. Redisson always acquires with an explicit `leaseTime`, then uses the shared extender when enabled. ZooKeeper locks are session-bound and have no TTL, so `autoExtend = true` is ignored with a warning. Group options do not expose `autoExtend`; use explicit `LockExtender` operations when a group slot must outlive its lease.

<!-- LEADER_BACKEND_DIAGNOSTICS:START -->
### Runtime backend diagnostics

Built-in electors expose their immutable capability descriptor through `LeaderBackendDiagnosticsProvider`. A static diagnostics read performs no backend I/O and reports connectivity as `NOT_CHECKED`. Active connectivity checks are separate, opt-in operations; `UNKNOWN` means the bounded check could not determine connectivity, not that the backend is healthy.

Spring Boot keeps the static `leaderBackendDiagnostics` Actuator endpoint disabled until it is explicitly enabled and exposed. Its backend health probe is also disabled by default under `bluetape4k.leader.observability.backend-health`; the default timeout is `500ms`. Ktor uses a separate `/management/leaderElection/diagnostics` route. `backendDiagnosticsRouteEnabled` and `backendConnectivityCheckEnabled` both default to `false`, and the connectivity timeout also defaults to `500ms`.

Diagnostics can disclose backend type, capability limits, connectivity status, and check timing. Protect Spring Actuator and Ktor management routes with authentication and network policy, and do not treat diagnostics as an ownership decision or readiness proof without an application-specific policy.

Built-in providers use the public `LeaderBackendDiagnosticsProbe.check` helper. It validates a positive, finite provider-native timeout, reads the clock once before invoking the callback, maps an ordinary `Exception` to `UNKNOWN`, and rethrows `CancellationException`, `InterruptedException` (after restoring the interrupt flag), and fatal `Error` values. A `NOT_CHECKED` callback result is invalid. Providers with an existing custom `checkConnectivity` or `diagnostics` override remain a compatibility escape hatch and own their exception behavior.

Connectivity results also carry the bounded `LeaderBackendConnectivityReason` value:

| Status | Reason | Meaning |
|---|---|---|
| `UP` | `CONNECTED` | The existing client confirmed that the backend is reachable at probe time. |
| `DOWN` | `DISCONNECTED` | The existing client confirmed that the backend is unavailable. |
| `UNKNOWN` | `CLIENT_STATE_UNCONFIRMED` | A bounded read-only check could not prove connectivity. |
| `UNKNOWN` | `PROVIDER_UNSUPPORTED` | The provider does not expose a supported active probe. |
| `UNKNOWN` | `PROVIDER_EXCEPTION` | An ordinary provider exception was normalized without retaining its details. |
| `NOT_CHECKED` | `NOT_CHECKED` | No active probe was requested; this is not a health signal. |

When an instrumented elector from `leader-micrometer` performs an active
`checkConnectivity` or `diagnostics(probe = true)` call, it increments the
`leader.backend.connectivity` counter once. The only tags are the sanitized
`backend.name`, `status`, and `reason`; passive diagnostics do not create a
series, and exception text, endpoints, credentials, and lock names are never
exported. `UNKNOWN` is a dashboard and warning signal, not an automatic
`DOWN` or page condition.

For the operational decision table and timeout/bypass runbook, see the
[backend connectivity observability guide](https://bluetape4k.github.io/manual/bluetape4k-leader/1.0/guides/backend-connectivity-observability/).
<!-- LEADER_BACKEND_DIAGNOSTICS:END -->

`@LeaderGroupElection` supports scalar, suspend, and `Mono` results, but rejects `Flux` and Kotlin `Flow` because per-slot stream lease extension is undefined. For long-running or unbounded single-leader streams, use `@LeaderElection(autoExtend = true)`.

For selection guidance, see [backend selection](https://bluetape4k.github.io/manual/bluetape4k-leader/1.0/guides/backend-selection/), [execution model selection](https://bluetape4k.github.io/manual/bluetape4k-leader/1.0/guides/execution-model-selection/), and [lease extension](https://bluetape4k.github.io/manual/bluetape4k-leader/1.0/core/lease-extension/). The runnable paths are indexed in [Examples](#examples).

## Examples

Runnable example modules under `examples/` demonstrate production scenarios across every supported backend. Examples are **not** publishing artifacts (`path.startsWith(":examples:")` is excluded from publish/sign/NMCP); copy them into your own service to start.

| Example | Backend | Scenario |
|---------|---------|----------|
| [`examples/batch-scheduler`](./examples/batch-scheduler) | Lettuce Redis | Periodic batch job (e.g. nightly settlement) — single execution across N instances |
| [`examples/migration-gate`](./examples/migration-gate) | Exposed JDBC (PostgreSQL/H2) | Boot-time schema migration gate — exactly one instance runs migrations |
| [`examples/webhook-poller`](./examples/webhook-poller) | MongoDB | External webhook polling — only the leader polls and dispatches |
| [`examples/cache-warmer`](./examples/cache-warmer) | Hazelcast | Per-partition leader election — exactly one instance warms each partition |
| [`examples/tenant-aggregator`](./examples/tenant-aggregator) | Exposed R2DBC | Coroutine-native multi-tenant aggregation — independent leader per tenant |
| [`examples/ktor-app`](./examples/ktor-app) | Ktor 3.x + Lettuce Redis | Ktor application using `LeaderElectionPlugin` and `Application.leaderScheduled()` |
| [`examples/prometheus-dashboard`](./examples/prometheus-dashboard) | Spring Boot + Lettuce Redis | Prometheus/Grafana dashboard for leader AOP metrics, backend connectivity, and scrape readiness |
| [`examples/etcd-reconciler`](./examples/etcd-reconciler) | etcd v3 | Control-plane reconciler where one node applies desired state |
| [`examples/consul-maintenance`](./examples/consul-maintenance) | Consul | Service maintenance/drain workflow where one instance performs the action |
| [`examples/dynamodb-export`](./examples/dynamodb-export) | DynamoDB Local / AWS DynamoDB | Scheduled export or billing job where only the leader writes export records |
| [`examples/zookeeper-scheduler`](./examples/zookeeper-scheduler) | ZooKeeper / Curator | Legacy scheduled job where only one node executes and contenders skip |
| [`examples/k8s-lease`](./examples/k8s-lease) | Kubernetes Lease | Low-level Lease acquire/release/reacquire workflow against K3s |
| [`examples/k8s-operator`](./examples/k8s-operator) | Kubernetes Lease + Spring Boot | 3-replica operator pattern where one pod runs the reconcile loop |
| [`examples/rate-limiter`](./examples/rate-limiter) | Lettuce Redis + Bucket4j | Leader-dispatched external API probes with shared rate limiting |
| [`examples/strategic-election`](./examples/strategic-election) | Local strategic election | Weighted health, capacity, success-rate, and idle-time scoring for a maintenance node |
| [`examples/virtual-thread-runner`](./examples/virtual-thread-runner) | Local virtual-thread election | High-concurrency leader-only maintenance runner using Java virtual threads |
| [`examples/redisson-watchdog`](./examples/redisson-watchdog) | Redisson Redis | Long-running leader-only job protected by bluetape4k lease auto-extension |

Run any example with `./gradlew :examples:<name>:run` (Docker required for Testcontainers-backed demos).

Testcontainers-backed examples create non-reusable containers by default. For an explicit developer-local opt-in,
set `testcontainers.reuse.enable=true` in `~/.testcontainers.properties`; the examples ignore this setting when
either the `CI` or `GITHUB_ACTIONS` marker is present, regardless of its value. Module tests always use one
non-reusable launcher container per test JVM. Reusable example containers remain developer-owned and are not
registered for shutdown removal.

## Quick Start

### Gradle

Choose the bluetape4k ecosystem version once through the central dependency platform. Do not coordinate a separate Leader BOM version in application builds:

```kotlin
val bluetape4kVersion = "<version>"

implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:$bluetape4kVersion"))
implementation("io.github.bluetape4k.leader:bluetape4k-leader-redis-redisson")
// Add only the backend and framework modules the service uses.
implementation("io.github.bluetape4k.leader:bluetape4k-leader-spring-boot")
```

The direct Leader release version is recorded by the versioned manual for provenance; it is not an additional consumer choice.

### Exposed JDBC (H2 / PostgreSQL / MySQL)

```kotlin
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderElector
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderGroupElectionOptions
import org.jetbrains.exposed.v1.jdbc.Database

val dataSource = HikariDataSource(HikariConfig().apply {
    jdbcUrl = "jdbc:postgresql://localhost:5432/mydb"
    username = "user"
    password = "pass"
})
val db = Database.connect(dataSource)

val election = ExposedJdbcLeaderElector(db)

val result = election.runIfLeader("daily-report-job") {
    generateReport()
}
// result == generateReport() on the leader, null on other nodes
```

Multi-leader group (JDBC):

```kotlin
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderGroupElector

val options = ExposedJdbcLeaderGroupElectionOptions(
    leaderGroupOptions = LeaderGroupElectionOptions(
        maxLeaders = 3,
        useDbTime = true, // use database server time for group ownership
    ),
)
val groupElection = ExposedJdbcLeaderGroupElector(db, options)

val result = groupElection.runIfLeader("parallel-batch") {
    processNextChunk()
}
```

`useDbTime` is an Exposed JDBC/R2DBC group option. It evaluates ownership and
active-slot expiry with one `SELECT CURRENT_TIMESTAMP` inside each ownership
transaction, so JVM clock skew does not change the lease boundary. It defaults
to `false`; when database time is unavailable, group state is reported
conservatively and `runIfLeader` skips rather than claiming ownership.

The option is available in `1.0.0`. The versioned manual pages are pinned to
that release provenance.

### Exposed R2DBC group (coroutine-native, 1.0.0+)

```kotlin
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2DbcSuspendLeaderGroupElector
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2dbcLeaderGroupElectionOptions
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

val db = R2dbcDatabase.connect("r2dbc:postgresql://user:pass@localhost:5432/mydb")
suspend fun runChunk() = ExposedR2DbcSuspendLeaderGroupElector(
    db,
    ExposedR2dbcLeaderGroupElectionOptions(
        leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 3, useDbTime = true),
    ),
).runIfLeader("parallel-batch") {
    processNextChunk()
}
```

For per-call construction inside a suspend scope, use
`ExposedR2DbcSuspendLeaderGroupElectorFactory` with
`factory.create(LeaderGroupElectionOptions(..., useDbTime = true))`.

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
    leaseTime = 30.seconds, // how long to hold the lock
    nodeId = "worker-a",    // id exposed by state snapshots
    minLeaseTime = 0.seconds, // lockAtLeastFor-style minimum lease retention
    autoExtend = true // renew single-leader leases while the action is running
)
val election = RedissonLeaderElector(client, options)
```

`minLeaseTime` is the `lockAtLeastFor` equivalent. Local electors wait before releasing; supported distributed backends delegate the remaining minimum lease to storage TTL so callers can return immediately.

`autoExtend` semantics vary by backend. Use the [backend capability matrix](#backend-capability-matrix) and [lease extension guide](https://bluetape4k.github.io/manual/bluetape4k-leader/1.0/core/lease-extension/) as the source of truth. `@LeaderGroupElection` does not support auto-extension.

### State snapshots

```kotlin
val single = election.state("daily-report-job")
if (single.isOccupied) {
    println("leader=${single.leader?.leaderId}")
}

val group = groupElection.state("parallel-batch")
println("active=${group.activeCount}/${group.maxLeaders}")
println("available=${group.availableSlots}")
println("leaders=${group.leaders.map { it.leaderId }}")
```

State APIs return best-effort snapshots for diagnostics and metrics. Do not use a snapshot to decide whether to run work; always use `runIfLeader` so the backend can acquire the lock atomically.

### Tenant namespaces

Use `forTenant()` when the same logical job must be isolated per SaaS tenant without changing backend configuration:

```kotlin
import io.bluetape4k.leader.forTenant

val tenantElection = election.forTenant("acme")
tenantElection.runIfLeader("daily-report-job") {
    generateTenantReport("acme")
}
// backend lockName: tenant:acme:daily-report-job

val tenantGroup = groupElection.forTenant("acme")
tenantGroup.runIfLeader("aggregation") {
    aggregateTenant("acme")
}
```

`forTenant()` is available for blocking, coroutine, group, and virtual-thread electors. The namespace separator `:` is reserved; tenant ids, custom prefixes, and tenant-local lock names must not contain `:`. Rename existing caller-facing lock names such as `batch:daily` before adding a tenant scope. The generated backend lock name must still satisfy the shared lock-name limit of 255 characters.

Tenant-scoped state snapshots return the full backend lock name, for example `tenant:acme:daily-report-job`. Do not pass `state().lockName` back to `runIfLeader()` on the same tenant-scoped elector; keep using the original caller-facing lock name such as `daily-report-job`.

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

### Ktor management route

`leader-ktor` can expose a JVM-local management route when the plugin option is enabled:

```kotlin
fun Application.module() {
    install(LeaderElectionPlugin) {
        leaderElection = redissonElector
        managementRouteEnabled = true
        managementLockNames("batch-job", "migration-gate")
    }
}
```

```http
GET /management/leaderElection
```

The route is disabled by default and runs on the main Ktor routing pipeline. Protect it with an authentication plugin, network policy, or an internal-only port before exposing it outside a trusted management boundary.

```json
{
  "locks": [
    {
      "name": "batch-job",
      "status": "Empty",
      "leaderId": null,
      "leaseExpiry": null
    }
  ]
}
```

## How `runIfLeader` Works

Multiple nodes call `runIfLeader` concurrently — only one acquires the lock and runs the action; the rest return `null`.

![How runIfLeader Works diagram](docs/images/readme-diagrams/bluetape4k-leader-sequence-02.png)

### Multi-leader group: slot-based semaphore

![Multi-leader group: slot-based semaphore diagram](docs/images/readme-diagrams/bluetape4k-leader-sequence-03.png)

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
| `StrategicLeaderGroupElector` | `T?` | Blocking strategic group election (advisory top-N candidate list) |
| `StrategicSuspendLeaderGroupElector` | `T?` | Coroutine strategic group election (advisory top-N candidate list) |

`runIfLeader(lockName, action)` — returns `action()` result on success, `null` if not elected.

### Distinguishing elected vs skipped: `LeaderRunResult`

`runIfLeader()` returns `null` for two distinct cases: (a) lock not acquired and (b) `action()` legitimately returning `null`. Use `runIfLeaderResult` (available on both `LeaderElector` and `LeaderGroupElector`) when you need to tell them apart — for example, in metrics or conditional post-processing:

```kotlin
when (val r = election.runIfLeaderResult("daily-job") { compute() }) {
    is LeaderRunResult.Elected -> println("elected, result=${r.value}")
    is LeaderRunResult.Skipped -> println("skipped — lock not acquired")
    is LeaderRunResult.ActionFailed -> println("action failed: ${r.cause.message}")
}
```

`LeaderRunResult` is a sealed interface with three variants:

- `Elected<T>(value: T?)` — lock/slot acquired and `action` completed. `value` may be `null`.
- `Skipped` — lock/slot was not acquired and `action` was not executed.
- `ActionFailed(cause)` — lock/slot was acquired and `action` started, but the action failed.

`runIfLeaderResult` is available for blocking electors, `runIfLeaderResultSuspend` for coroutine electors, and `runAsyncIfLeaderResult` for `CompletableFuture` / virtual-thread electors. `CancellationException` is not wrapped as `ActionFailed`: blocking and suspend APIs rethrow it, while async and virtual-thread APIs complete exceptionally (for `join()`, expect `CompletionException` wrapping the cancellation; `isCancelled()` is not guaranteed). Blocking APIs also rethrow `InterruptedException` after restoring the interrupt flag.

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

### Strategic group election (blocking/coroutine)

Strategic group election evaluates one observed candidate snapshot and runs the action only on nodes in the selected top-N set. Use `FifoGroupElectionStrategy` for registration order or `ScoredGroupElectionStrategy` for deterministic score-based ordering:

```kotlin
import io.bluetape4k.leader.lettuce.LettuceStrategicLeaderGroupElector
import io.bluetape4k.leader.strategy.strategies.FifoGroupElectionStrategy

val election = LettuceStrategicLeaderGroupElector(connection, nodeId = "node-1")
election.registerCandidate("batch-shard", CandidateInfo("node-1"), ttl = 5.minutes)

val result = election.runIfLeader(
    "batch-shard",
    FifoGroupElectionStrategy,
    maxLeaders = 2,
) { processShard() }
```

`registerCandidate` replaces the complete `CandidateInfo` record, so do not use it as a
heartbeat after `updateResult`: a stale candidate record can roll back the result counters and
timestamps. Use `refreshCandidate` for an existing candidate instead. It preserves
`registeredAt`, `lastStartTime`, `lastCompletionTime`, `successCount`, and `failureCount`,
while replacing `metadata` and applying the requested TTL. Redis implementations perform
this merge atomically; a refresh for an expired or missing Redis candidate is a no-op, so
call `registerCandidate` for initial enrollment. `updateResult` keeps the current TTL,
whereas `refreshCandidate(..., Duration.ZERO)` makes the candidate persistent.

`maxLeaders` is an advisory top-N limit for the candidate list read by that invocation; it is not a global distributed concurrency cap. Different nodes can observe different candidate lists and their union can exceed N. Use `LeaderGroupElector` when a hard global slot limit is required. Redis strategic group registries use separate backend-qualified namespaces (`leader:strategy:group-candidates:lettuce:v1` and `leader:strategy:group-candidates:redisson:v1`) from strategic single-leader registries. A non-zero candidate TTL requires re-registration or heartbeat before expiry; `Duration.ZERO` is persistent, while the Local implementation keeps candidates for the process lifetime and ignores TTL. Lettuce keeps the candidate index independent from per-candidate TTLs, so an expiring candidate cannot hide a persistent candidate with the same lock name. Custom strategies must return a complete, non-overlapping winner/elimination partition of the same candidate list, or the elector fails fast with `IllegalArgumentException`.

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

    @LeaderElection(name = "event-stream", autoExtend = true)
    fun streamEvents(): Flux<Event> = eventRepository.stream()

    @LeaderElection(name = "bounded-flow", streamBounded = true)
    fun boundedFlow(): Flow<Event> = eventRepository.findRecent()
}
```

Stream return rules:

- `@LeaderElection` supports `T?`, `suspend T?`, `Mono<T>`, `Flux<T>`, and Kotlin `Flow<T>`.
- Use `autoExtend = true` for long-running or unbounded streams.
- Use `streamBounded = true` only when the stream is known to finish within the lease window.
- Unsafe `Flux` / `Flow` signatures fail fast in the validator and at subscription/collection time.
- `@LeaderGroupElection` supports `T?`, `suspend T?`, and `Mono<T>`.
- `@LeaderGroupElection` `Flux<T>` / `Flow<T>` streams remain unsupported on the `1.0.0+` development line. They are rejected in startup validation and again at subscription/collection time because group lease extension is not defined per slot.

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

### Leader Election Actuator endpoint

`leader-spring-boot` registers an opt-in `leaderElection` Actuator endpoint for JVM-local lock status diagnostics:

```yaml
bluetape4k:
  leader:
    observability:
      lock-names:
        - batch-job
        - migration-gate

management:
  endpoint:
    leaderElection:
      enabled: true
  endpoints:
    web:
      exposure:
        include: health,leaderElection
```

```http
GET /actuator/leaderElection
```

```json
{
  "backend": "redis",
  "stateProviderBean": "redisLeaderElector",
  "stateSupported": true,
  "locks": [
    {
      "name": "batch-job",
      "status": "Occupied",
      "leaderId": "node-1",
      "leaseExpiry": "2026-05-16T00:00:00Z"
    }
  ]
}
```

`LeaderElectionEventPublisher` is the framework-neutral observability surface. Kotlin users can collect the hot
`events` `Flow`; framework adapters and Java users can register callback consumers with `onEvent`, `onElected`,
`onRevoked`, or `onSkipped` and close the returned handle during shutdown. Spring Boot Actuator, Ktor management
routes, Micrometer, logging, tracing, and custom dashboards should adapt from this core event stream instead of
introducing framework-specific event contracts.

### Lease-extension observation

This API is included in `1.0.0`; use the release-pinned manual for the complete
contract and adapter guidance.

`LockExtender` and `LeaderLeaseAutoExtender` publish the same framework-neutral terminal event contract. Register an
observer only when the application needs lease-extension diagnostics:

```kotlin
val registration = LeaderLeaseExtensionObservers.addObserver { event ->
    logger.info {
        "lease extension source=${event.source} execution=${event.execution} " +
            "outcome=${event.outcome::class.simpleName}"
    }
}

// This blocking example belongs inside a matching active @LeaderElection or @LeaderGroupElection scope; otherwise it
// returns NotHeld with context = null. The same applies inside a direct elector's active lease body. In a suspend
// scope, use extendActiveLockDetailedSuspend(60.seconds) inside the suspend function instead.
try {
    LockExtender.extendActiveLockDetailed(60.seconds)
} finally {
    registration.close()
}
```

`#529` continues to cover acquire/execution observations; this `#559` hook covers terminal lease-extension attempts.
`event.source` distinguishes `USER` calls from the `WATCHDOG`; `event.execution` distinguishes `BLOCKING` from
`SUSPEND`. `event.outcome` is the existing `ExtendOutcome` (`Extended`, `Rejected`, `NotHeld`, `WrongThread`, or
`BackendError`), and `elapsedNanos` is the caller-side delegate duration. `Rejected` can mean a watchdog reservation
failed, a user bounded operation queue was full, or a queued user operation timed out before its command completed;
that command may still run later. It is a skip signal, not proof that no backend work will occur. The observer registry is process-local and dispatches
through bounded, non-blocking in-flight admission. A saturated observer increments
`LeaderLeaseExtensionObservers.droppedCount()` instead of waiting for a permit or callback. Registration count and
callback fan-out are not bounded by this registry, so applications should keep registrations small and callbacks
short. `droppedCount()` is therefore separate from `ExtendOutcome.Rejected`: it counts observer-delivery admission drops.
Close removes only that registration; an already accepted callback may still finish, and callback ordering is
not guaranteed.

`addObserver` remains a process-wide wildcard API. Spring's automatic Micrometer adapter is narrower: each
`ObservationRegistry` identity owns an opaque execution scope, so two application contexts with different registries
receive only their own AOP-attributed `USER` and `WATCHDOG` events. Parent and child contexts that intentionally share
one registry share one telemetry domain. Calls made outside `@LeaderElection`/`@LeaderGroupElection`, including direct
elector calls and Reactor callbacks outside the aspect-owned coroutine bridge, fail closed for automatic Spring
telemetry but still reach explicit global observers. Do not register the same Micrometer observer both globally and
automatically, because that produces duplicate observations.

The snippet above closes after one explicit `USER` attempt. To observe `WATCHDOG` ticks, keep the registration open for
the entire single-leader action or component lifetime with `autoExtend = true`, then close it during shutdown. Group
elections support explicit `LockExtender` calls inside their active slot bodies, but they do not produce `WATCHDOG`
events because group auto-extension is disabled.

Callback exceptions do not change the extension result. `CancellationException` and `Error` from the extension path
are not flattened into an outcome or published as events. `BackendError.cause` remains the original backend `Exception`;
core does not redact it, so custom observers must sanitise the cause before logging or exporting. `LeaderLeaseExtensionContext.toString()` is redacted, so applications should still
avoid logging raw `lockName` or `auditLeaderId`. A fail-open `NotHeld` event still carries its lock name in `context` with
`auditLeaderId = null`; scope-free and named-mismatch events have `context = null`. The [lease extension guide](https://bluetape4k.github.io/manual/bluetape4k-leader/1.0/core/lease-extension/)
contains the complete contract and adapter guidance.

### Audit export to an HTTP/webhook sink

For sanitized history or lifecycle delivery, compose the core
`HttpLeaderAuditExporter` with an application-owned `LeaderAuditPayloadEncoder`:

```kotlin
val endpoint = LeaderAuditTrustedHttpsEndpoint.trusted(
    URI("https://audit.example.test/v1/leader-events"),
)
val client = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NEVER)
    .build()
val exporter = MicrometerLeaderAuditExporter(
    delegate = HttpLeaderAuditExporter(
        client = client,
        endpoint = endpoint,
        headers = mapOf("Authorization" to "Bearer ${System.getenv("AUDIT_WEBHOOK_TOKEN")}"),
        encoder = LeaderAuditPayloadEncoder { event ->
            LeaderAuditHttpPayload.of("text/plain; charset=utf-8", event.toString().toByteArray())
        },
        exportOptions = exportOptions,
        httpOptions = LeaderAuditHttpOptions.defaults(),
    ),
    registry = meterRegistry,
)
```

The adapter uses `POST`, bounded retries, and `BodyHandlers.discarding()`. Only
`Content-Type` and `Authorization` headers are accepted; redirects are disabled.
`LeaderAuditTrustedHttpsEndpoint` validates the HTTPS syntax and records that the
caller owns endpoint allow-list and DNS/SSRF policy. `submit` returning `ACCEPTED`
means admission only, so receivers should be idempotent. JSONL and OpenTelemetry
transports remain separate application choices.

`bluetape4k.leader.observability.lock-names` seeds the JVM-local status registry before the first runtime event. Listener-aware electors can also add names as they observe lifecycle events. The fallback `LeaderElectionEventPublisher` is publisher-only and never becomes a `LeaderElector` candidate, so existing elector injection remains stable.

Spring diagnostics, readiness, and the Actuator endpoint select from both blocking and suspend
`LeaderElectionState` beans. A non-local suspend backend therefore wins over the blocking local fallback.
When more than one non-local backend is active, set
`bluetape4k.leader.observability.state-provider-bean` to the bean used for operational state.
If that provider does not support audit state, the endpoint reports `stateSupported=false` and lock
status `Unsupported`; opt-in readiness reports `UNKNOWN` instead of a false `UP`.

`LeaderLeaseAutoExtender` remains JVM-global. Live Spring contexts may share defaults or the same
explicit `watchdog-threads` / `watchdog-async-extend` values, but a context with conflicting explicit
values is rejected before it can overwrite the active scheduler configuration. The scheduler stops only
after the last registered context closes.

---

## Management Endpoints

Spring Boot applications can expose a best-effort leader status endpoint through Actuator. Enable
leader observability beans and the endpoint explicitly:

```yaml
bluetape4k:
  leader:
    observability:
      enabled: true
      lock-names:
        - batch-job
        - migration-gate

management:
  endpoint:
    leaderElection:
      enabled: true
  endpoints:
    web:
      exposure:
        include: leaderElection
```

The HTTP path is `GET /actuator/leaderElection`. Lock names come from the JVM-local
`LeaderElectionStatusRegistry`: configure static names with
`bluetape4k.leader.observability.lock-names`, or let Spring AOP observations register names as
leader-election methods run. The endpoint does not enumerate backend locks.
The response identifies the selected backend and provider bean. In multi-backend applications, configure
`bluetape4k.leader.observability.state-provider-bean`; otherwise endpoint/readiness startup fails rather
than selecting an arbitrary backend.

Ktor applications can expose the same status shape with `leaderElectionManagementRoute()`:

```kotlin
install(LeaderElectionPlugin) {
    leaderElection = redissonElector
    managementRouteEnabled = true
    managementLockNames("batch-job", "migration-gate")
}

leaderElectionManagementRoute()
```

The Ktor route defaults to `GET /management/leaderElection` and is installed on the application's
main routing pipeline. Protect it with authentication, network policy, or a dedicated internal port
before exposing it outside a trusted management boundary.

---

## Micrometer Metrics

When using Spring Boot AOP (`@LeaderElection`), add `leader-micrometer` to expose Prometheus/Datadog metrics automatically.

### Dependency

```kotlin
implementation("io.github.bluetape4k.leader:bluetape4k-leader-spring-boot")
implementation("io.github.bluetape4k.leader:bluetape4k-leader-micrometer")
```

`MicrometerLeaderAopMetricsRecorder` is auto-registered when a `MeterRegistry` bean is present. Disable with:

```yaml
bluetape4k:
  leader:
    aop:
      metrics:
        enabled: false
```

Metric tag values are sanitized before export. By default, dynamic `lock.name` values are collapsed to `redacted-lock`, opt-in Observation `leader.id` values are collapsed to `redacted-leader`, and the active diagnostics meter emits only sanitized, bounded `backend.name` values. Other built-in meter paths do not emit `backend.name`. Use `bluetape4k.leader.aop.metrics.tags.lock-name.mode=RAW` only for small static job sets; use `HASH` or `TRUNCATE` when dashboards need bounded correlation for dynamic names.

### Meter Catalog

| Meter name | Type | Description |
|------------|------|-------------|
| `leader.aop.attempts` | Counter | Lock acquisition attempts per `lock.name` |
| `leader.aop.acquired` | Counter | Successful leader elections |
| `leader.aop.acquire.duration` | Timer | Time from lock attempt to successful acquisition |
| `leader.aop.lock.not.acquired` | Counter | Skipped executions; tagged with `reason` (`CONTENTION` / `BACKEND_ERROR`) |
| `leader.aop.execution.duration` | Timer | Elapsed time of the leader action |
| `leader.aop.task.failed` | Counter | Action body exceptions; tagged with `exception` class name |
| `leader.aop.active` | Gauge | Currently running leader actions (JVM-local) |
| `shedlock.leader.acquired` | Counter | Decorator-based successful leader executions |
| `shedlock.leader.not_acquired` | Counter | Decorator-based skipped executions |
| `shedlock.leader.duration` | Timer | Decorator-based leader action duration |
| `shedlock.leader.active` | Gauge | Decorator-based currently running leader actions (JVM-local) |

All meters use the exported `lock.name` tag after cardinality control. Micrometer's `NamingConvention` converts names per backend (e.g., `leader_aop_attempts_total` for Prometheus).

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

Pass `lockName = "static-job"` to any wrapper to use a fixed `lock.name` tag before sanitization; omit it to use the per-call lock name.

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
| etcd | Yes | No |
| Consul | Preview single/group blocking/async/coroutine + Spring Boot | No |
| DynamoDB | Preview single/group blocking/async/coroutine + virtual thread + Spring Boot | No |
| Hazelcast | Yes | Yes |
| ZooKeeper | Yes | No |

## Requirements

- JVM 25+
- Kotlin 2.4+

## Publication metadata validation

Release, snapshot, and publishable-module upload tasks run this gate before
uploading a Maven publication. To run it standalone, use:

```bash
./gradlew verifyPublishedPomLicenses
```

The gate regenerates all 17 publishable POMs and verifies the MIT license name,
URL, and `repo` distribution. It also rejects any remaining Apache license
metadata before publication, and requires both README locales to retain the MIT
badge link and `[LICENSE](LICENSE)` reference.

## License

MIT License — see [LICENSE](LICENSE).
