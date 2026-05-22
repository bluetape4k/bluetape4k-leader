# Changelog

All notable changes to `bluetape4k-leader` are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

---

## [0.1.0] — 2026-05-16

First public release of `bluetape4k-leader`. All APIs are stable unless noted as experimental.

### Breaking Changes

- **`runIfLeader()` never throws on lock contention**: returns `null` instead of propagating
  backend exceptions when the lock is not acquired — aligns with ShedLock skip-on-contention
  semantics. `CancellationException` and `InterruptedException` are still rethrown. (PR #15)

  ```kotlin
  // Null means "not elected" or "action threw" — check logs
  val result = leaderElector.runIfLeader("job") { riskyWork() }

  // To preserve exception propagation, wrap in the action:
  leaderElector.runIfLeader("job") {
      try { riskyWork() } catch (e: MyException) { handleError(e); throw e }
  }
  ```

- **`leader-exposed-jdbc`**: `ExposedJdbcLeaderElector.runIfLeader()` now returns `null` instead
  of rethrowing action exceptions. `CancellationException` and `InterruptedException` are still
  rethrown. (issue #50)

- **`leader-exposed-r2dbc`**: Same change for `ExposedR2DbcSuspendLeaderElector.runIfLeader()`.
  `CancellationException` is still rethrown.

- **`leader-exposed-jdbc` / `leader-exposed-r2dbc`**: Elector factories now accept an optional
  `historyRecorder` parameter (`SafeLeaderHistoryRecorder?` / `SuspendSafeLeaderHistoryRecorder?`).
  The previous `recordHistory` option in election options is superseded.

- **`LeaderElection` / `LeaderGroupElection` renamed** to `LeaderElector` / `LeaderGroupElector`
  for consistency across all interfaces. (PR #106, #123, #125)

- **Duration APIs**: migrated from `java.time.Duration` to `kotlin.time.Duration`. (PR #126)

- **`LeaderElectionEvent.Elected`** now carries optional `leaderId: String?` and
  `leaseExpiry: Instant?` fields (both default to `null`). Compiled callers that construct
  `Elected(lockName)` positionally will fail to link against new bytecode; recompilation is
  required (source-compatible).

- **Spring Boot 3/4 split consolidated**: `leader-spring-boot-common`, `leader-spring-boot3`, and
  `leader-spring-boot4` merged into a single `leader-spring-boot` module. (PR #105)

### Added

**`leader-core`** — Core interfaces and local in-process implementations:

- `LeaderElector` — blocking single-leader interface
- `AsyncLeaderElector` — `CompletableFuture`-based async interface
- `VirtualThreadLeaderElector` — virtual-thread-per-election interface
- `SuspendLeaderElector` — Kotlin coroutine suspend interface
- `LeaderGroupElector` — blocking multi-leader (semaphore) interface
- `SuspendLeaderGroupElector` — coroutine multi-leader interface
- `LeaderElectionOptions(waitTime, leaseTime)` — shared option data class
- `LeaderGroupElectionOptions(maxLeaders, waitTime, leaseTime)` — group option data class
- Local implementations: `LocalLeaderElector`, `LocalLeaderGroupElector`,
  `LocalSuspendLeaderElector`, `LocalSuspendLeaderGroupElector`, `LocalAsyncLeaderElector`,
  `LocalVirtualThreadLeaderElector`
- `LockAssert` — `assertLocked()` / `assertLocked(lockName)` / `isLocked()` and suspend variants
- `LockExtender` — `extendActiveLock(Duration): Boolean` + detailed sealed `ExtendOutcome` result
  + suspend variants
- `LeaderLockHandle` sealed class (`Real` / `FailOpen`) — explicit lease handle
- `LeaderLeaseAutoExtender` — periodic background lease renewal with `shutdown()` / `restart()`
- `ListeningLeaderElector` / `ListeningLeaderGroupElector` — listener-aware decorators with a hot
  `events: Flow<LeaderElectionEvent>` stream (issue #40, PR #146)
- `TenantScopedLeaderElectors` — `forTenant(tenantId)` extension functions for multi-tenant
  lock-name scoping (issue #42)
- Strategic election API (issue #29, #31, #32):
  - `CandidateInfo`, `ElectionStrategy`, `CandidateScorer` interfaces
  - Built-in strategies: `FifoElectionStrategy`, `RandomElectionStrategy`, `ScoredElectionStrategy`
  - Built-in scorers: `IdleTimeScorer`, `SuccessRateScorer`, `RecentSuccessScorer`, `WeightedScorer`
  - Redis `CandidateRegistry` (Redisson sorted-set/hash + TTL; Lettuce variant included)
- `LeaderSlot` audit identity propagation: `LeaderSlot(lockName, leaderId)` propagates to
  `LeaderRunResult.Elected.leaderId` (issue #72)

**`leader-redis-lettuce`** — Lettuce Redis backend:

- `LettuceLeaderElector` — blocking, uses `SET NX PX` via `LettuceLock`
- `LettuceLeaderGroupElector` — blocking multi-leader via `LettuceSlotTokenGroup` (ZSET + Lua TTL)
- `LettuceSuspendLeaderElector` — coroutine single-leader
- `LettuceSuspendLeaderGroupElector` — coroutine multi-leader
- `LettuceLock`, `LettuceSuspendLock` — Redis distributed lock primitives (self-contained, no `bluetape4k-lettuce` dependency)

**`leader-redis-redisson`** — Redisson Redis backend:

- `RedissonLeaderElector` — blocking via `RLock.tryLock()`
- `RedissonLeaderGroupElector` — blocking multi-leader via `RPermitExpirableSemaphore`
- `RedissonSuspendLeaderElector` — coroutine single-leader with PID-seeded Snowflake lock ID
- `RedissonSuspendLeaderGroupElector` — coroutine multi-leader

**`leader-exposed-core`**: Shared Exposed table DDL (`LeaderLockTable`, `LeaderGroupLockTable`) for
JDBC and R2DBC backends. (issue #23)

**`leader-exposed-jdbc`** — Exposed JDBC blocking backend (issue #21, PR #52):

- `ExposedJdbcLeaderElector` — blocking single-leader (sync + `CompletableFuture` async)
- `ExposedJdbcLeaderGroupElector` — blocking multi-leader
- H2/PostgreSQL/MySQL Testcontainers integration tests

**`leader-exposed-r2dbc`** — Exposed R2DBC coroutine backend (issue #22, PR #62):

- `ExposedR2DbcSuspendLeaderElector` — coroutine single-leader
- `ExposedR2DbcSuspendLeaderGroupElector` — coroutine multi-leader
- R2DBC PostgreSQL Testcontainers integration tests

**`leader-mongodb`** — MongoDB backend (issue #8, PR #46):

- `MongoLeaderElector` — blocking, `findOneAndUpdate` upsert + `deleteOne(token)` owner-only release
- `MongoSuspendLeaderElector` — coroutine, Kotlin coroutine driver
- `MongoLeaderGroupElector` — blocking multi-leader (`lockName:slot:N` slot model)
- `MongoSuspendLeaderGroupElector` — coroutine multi-leader (dual-collection design)
- TTL index auto-creation at startup
- 82.4% line coverage (42 tests, Testcontainers MongoDB)

**`leader-hazelcast`** — Hazelcast `IMap` token-lock backend (issue #9):

- `HazelcastLeaderElector`, `HazelcastLeaderGroupElector` — blocking single/multi-leader
- `HazelcastSuspendLeaderElector`, `HazelcastSuspendLeaderGroupElector` — coroutine variants

**`leader-zookeeper`** — ZooKeeper/Curator backend. (PR #138)

**`leader-micrometer`** — Micrometer metrics integration:

- `MicrometerLeaderElectionListener` records `leader.election.events` with `lock.name` and `event` tags (issue #40, PR #146)

**`leader-spring-boot`** — Spring Boot 4 auto-configuration + AOP:

- `@LeaderElection` / `@LeaderGroupElection` annotations with AspectJ compile-time weaving (CTW)
- Supports `suspend`, `Mono`, `Flux`, and Kotlin `Flow` return types (#74, #90, #91)
  - `@LeaderElection(streamBounded = true)` explicit opt-in for bounded streams
- `LeaderAnnotationValidatorBeanPostProcessor` — startup validation; blocks unsafe return types
  (`CompletableFuture`, `Deferred`, etc.) (PR #79)
- `LockAssert` / `LockExtender` integration in AOP aspect (issue #79)
- `LeaderMetricsHealthIndicator` — Spring Boot Actuator health indicator (registered as `leaderMetricsHealthIndicator`)
- `LeaderLeaseAutoExtenderLifecycle` — context-lifecycle-aware auto-extender integration
- Backend auto-configurations: Lettuce, Redisson, Exposed JDBC, Exposed R2DBC, MongoDB, Hazelcast
- `LeaderProperties` — `bluetape4k.leader.*` configuration properties

**`leader-ktor`** — Ktor 3.x integration (issue #37, PR #164):

- `LeaderElectionPlugin` — `createApplicationPlugin` DSL, `SuspendLeaderElector`-based
- `Application.leaderScheduled(lockName, period) { }` — Spring `@Scheduled`-style leader-only
  periodic task helper; auto-cancelled on `ApplicationStopped`

**`leader-bom`** — Bill of Materials for consumers. All `leader-*` modules included; BOM users
do not need to specify individual versions.

**`examples/`** — Runnable example applications (issue #36):

- `batch-scheduler` — Lettuce Redis periodic batch single-execution (PR #159)
- `migration-gate` — Exposed JDBC boot-time schema migration gate (PR #160)
- `webhook-poller` — MongoDB single-instance webhook polling (PR #161)
- `cache-warmer` — Hazelcast per-partition leader cache warming (PR #162)
- `tenant-aggregator` — Exposed R2DBC coroutine multi-tenant aggregation (PR #163)
- `ktor-app` — Ktor 3.x + Lettuce Redis `leaderScheduled()` demo (PR #166)
- `prometheus-dashboard` — Spring Boot + Prometheus/Grafana leader metrics dashboard

**CI/CD** (issue #13, #35, PR #19, #20, #44, #135):

- GitHub Actions build, test, secret-scan, Gradle wrapper validation
- Nightly SNAPSHOT auto-publish (on test success only)
- Parallel test jobs for Lettuce and Redisson backends

### Changed

- `LeaderElectionAspect` / `LeaderGroupElectionAspect` outer catch narrowed to `Exception` (not
  `Throwable`) to let `OutOfMemoryError` / `StackOverflowError` propagate.
- `LeaderElectionOptions`, `LeaderGroupElectionOptions`, `LeaderGroupState` validate eagerly in
  `init {}` (`waitTime ≥ 0`, `leaseTime > 0`, `maxLeaders ≥ 1`). (PR #25)
- `ExposedJdbcGroupLock.tryLock()` return type changed to `Boolean?`: `null` = DB error,
  `false` = slot contention, `true` = acquired. (issue #60, PR #63)
- Suspend tests use `runBlocking(Dispatchers.IO)` instead of `runTest` for real-IO
  (MongoDB / Testcontainers) tests.
- CI uses paths-filter and retry configuration to reduce unnecessary work and transient failures.
  (PR #135)
- Prometheus export coverage verified through `PrometheusServer` scrape tests. (PR #144)
- `leader-bom` NMCP aggregation and Central Snapshots publishing fixed. (PR #140)
- factory `create()` I/O failures now respect the configured failure mode. (PR #107)
- `@ConfigurationProperties` added to AOP properties binding where missing. (PR #93)

### Fixed

- **Coroutine cancellation safety**: `unlock`/`release` in all coroutine backends (Lettuce,
  Redisson, Hazelcast, MongoDB) wrapped in `withContext(NonCancellable)` to prevent lock leaks
  on cancellation. (PR #25, review 2026-05-01)
- **`CancellationException` rethrow**: `catch(CancellationException) { throw e }` added before
  all `catch(Exception)` blocks, including inside `withContext(NonCancellable)`. (PR #45)
- **Lettuce observability**: `runCatching { unlock }` failures now logged via `.onFailure { log.warn }`;
  previously, token-mismatch / Redis errors were silently discarded.
- **`ExposedJdbcGroupLock.isHeldByCurrentInstance()`**: added missing token + `lockedUntil > NOW()`
  check. (issue #59, PR #63)
- **`ExposedJdbcGroupLock.tryLock()` DB error propagation**: `Boolean?` tri-state separates DB
  errors from slot contention. (issue #60, PR #63)
- **Exposed lock SELECT predicate**: `lockedUntil > NOW()` added to JDBC `tryAcquireOnce` Step 3
  SELECT to prevent split-brain; symmetric with R2DBC. (review 2026-05-04)
- **`leader-redis-redisson` coroutine lock ID**: replaced `bluetape4k-idgenerators` compileOnly
  dependency (caused `ClassNotFoundException` at runtime) with a self-contained PID-seeded
  mini-Snowflake generator. (issue #3, PR #17)
- **`leader-redis-lettuce`**: ported `LettuceLock` primitives directly, removing the runtime
  dependency on `bluetape4k-lettuce`. (PR #2)
- **Kover coverage aggregation**: fixed missing-module aggregation bug in the CI coverage script.
- **Deprecated API replaced**: `TimebasedUuid.Epoch` → `Uuid.V7` (Kotlin 2.3+).

### Removed

The following deprecated APIs were removed before 0.1.0 GA (#264):

| Item | Replacement |
|------|-------------|
| `LeaderLease.leaderId` property | `LeaderLease.auditLeaderId` |
| `LeaderLeaseAutoExtender.start(Boolean lambda)` overload | `start(ExtendDelegate)` form |
| `HistoryStatus` typealias (`HistoryStatus.kt` deleted) | `LeaderHistoryStatus` |
| `RetryStrategy` typealias (`RetryStrategy.kt` deleted) | (zero callers — removed) |
| `ExposedJdbcGroupLock.extend()` | (no production callers — removed) |
| `ExposedJdbcLock.extend()` | (no production callers — removed) |
| `MongoLock.extend()` | (no callers — removed) |
| `MongoSuspendLock.extend()` | (no callers — removed) |
| `LettuceSemaphore` class | `LettuceLeaderGroupElector` (slot-token TTL model) |
| `LettuceSuspendSemaphore` class | `LettuceSuspendLeaderGroupElector` |

---

[Unreleased]: https://github.com/bluetape4k/bluetape4k-leader/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/bluetape4k/bluetape4k-leader/releases/tag/v0.1.0
