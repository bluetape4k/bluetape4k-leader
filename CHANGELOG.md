# Changelog

All notable changes to `bluetape4k-leader` are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Fixed

- **leader-redis-lettuce coroutine cancellation safety**: `LettuceSuspendLeaderElection` and `LettuceSuspendLeaderGroupElection` now wrap unlock/release in `withContext(NonCancellable)` so the Redis key/permit is reliably released when the calling coroutine is cancelled mid-`action`. Previously, cancellation between `action()` and the `finally` block could leak the Redis lock until lease expiry. (daily review)
- **Lettuce backend observability**: `runCatching { unlock/release }` blocks in Lettuce single- and group-leader implementations now surface failures via `.onFailure { log.warn }`. Token mismatch / Redis errors during release were previously dropped silently. (daily review)

### Changed

- **Argument validation pushed into option/state types**: `LeaderElectionOptions`, `LeaderGroupElectionOptions`, and `LeaderGroupState` now reject invalid arguments in their `init {}` blocks (`waitTime` ≥ 0, `leaseTime` > 0, `maxLeaders` ≥ 1, `activeCount ∈ 0..maxLeaders`, `lockName` non-blank). Previously these only blew up later inside semaphore/lock code. (daily review)
- **Redisson KDoc accuracy**: removed stale "throws RedisException on contention" claims and replaced with the actual ShedLock-style `null` return contract; `@throws RedisException` now only documents the interrupt path. (daily review)
- **Suspend interface contract** (`SuspendLeaderElection`, `SuspendLeaderGroupElection`): added explicit cancellation contract to KDoc — implementations must release the lock/slot and rethrow `CancellationException`. (daily review)

### Added

- **`leader-mongodb`**: MongoDB `findOneAndUpdate` + TTL index 기반 분산 락 백엔드 (issue #8, PR #46)
  - `MongoLock` — sync blocking, `findOneAndUpdate` upsert + `deleteOne(token)` 소유자 전용 해제
  - `MongoSuspendLock` — Kotlin coroutine driver 기반 suspend 분산 락
  - `MongoLeaderElection` — blocking 단일 리더 선출 (sync + `CompletableFuture` async)
  - `MongoSuspendLeaderElection` — coroutine 단일 리더 선출
  - `MongoLeaderGroupElection` — blocking 복수 리더 선출 (`lockName:slot:N` 슬롯 기반)
  - `MongoSuspendLeaderGroupElection` — coroutine 복수 리더 선출 (이중 컬렉션 설계)
  - 라인 커버리지 82.4% (42 테스트)
  - Testcontainers MongoDB 통합 테스트

### Planned

- `leader-exposed-core`/`leader-exposed-jdbc`/`leader-exposed-r2dbc` — Exposed backends (issue #23, #21, #22)
- `leader-hazelcast` — Hazelcast backend (issue #33)
- `leader-zookeeper` — ZooKeeper/Curator backend (issue #34)
- `leader-micrometer` — Micrometer metrics integration (issue #10)
- `leader-spring-boot3` — Spring Boot 3 auto-configuration (issue #11)
- `leader-spring-boot4` — Spring Boot 4 auto-configuration (issue #12)
- Expanded test coverage (issue #4)

---

## [0.1.0-SNAPSHOT] — In Progress

### Added

- **`leader-core`**: Core interfaces and local in-process implementations
  - `LeaderElection` — blocking single-leader interface
  - `AsyncLeaderElection` — `CompletableFuture`-based async interface
  - `VirtualThreadLeaderElection` — virtual-thread-per-election interface
  - `SuspendLeaderElection` — Kotlin coroutine suspend interface
  - `LeaderGroupElection` — blocking multi-leader (semaphore) interface
  - `SuspendLeaderGroupElection` — coroutine multi-leader interface
  - `LeaderElectionOptions(waitTime, leaseTime)` — shared option data class
  - `LeaderGroupElectionOptions(maxLeaders, waitTime, leaseTime)` — group option data class
  - Local implementations: `LocalLeaderElection`, `LocalLeaderGroupElection`, `LocalSuspendLeaderElection`, `LocalSuspendLeaderGroupElection`, `LocalAsyncLeaderElection`, `LocalVirtualThreadLeaderElection`

- **`leader-redis-lettuce`**: Lettuce-based Redis backend
  - `LettuceLeaderElection` — blocking, uses `SET NX PX` via `LettuceLock`
  - `LettuceLeaderGroupElection` — blocking multi-leader via `LettuceSemaphore`
  - `LettuceSuspendLeaderElection` — coroutine single-leader
  - `LettuceSuspendLeaderGroupElection` — coroutine multi-leader
  - `LettuceLock`, `LettuceSuspendLock` — Redis distributed lock primitives (ported from `bluetape4k-lettuce`, no external dependency)
  - `LettuceSemaphore`, `LettuceSuspendSemaphore` — Redis distributed semaphore primitives

- **`leader-redis-redisson`**: Redisson-based Redis backend
  - `RedissonLeaderElection` — blocking via `RLock.tryLock()`
  - `RedissonLeaderGroupElection` — blocking multi-leader via `RSemaphore`
  - `RedissonSuspendLeaderElection` — coroutine single-leader with PID-seeded Snowflake lock ID
  - `RedissonSuspendLeaderGroupElection` — coroutine multi-leader via `RSemaphore`

- **`leader-bom`**: Bill of Materials for consumers

### Changed

- `runIfLeader()` return type changed from throwing `RedisException` on lock failure to returning `T?` (`null` = not elected, skip) — aligns with ShedLock skip-on-contention semantics (PR #15)

### Fixed

- `leader-redis-redisson` coroutine lock ID: replaced dependency on `bluetape4k-idgenerators` (which was `compileOnly` upstream, causing `ClassNotFoundException` at runtime) with a self-contained PID-seeded mini-Snowflake generator: `timestamp(42) | pid%(2^10)(10) | seq(12)` (issue #3, PR #17)
- `leader-redis-lettuce`: ported `LettuceLock`/`LettuceSemaphore` primitives directly, removing runtime dependency on `bluetape4k-lettuce` (PR #2)
- Test infrastructure made self-contained: each module uses Testcontainers `GenericContainer` directly instead of `bluetape4k-redisson` test utilities (issue #3, PR #17)

---

[Unreleased]: https://github.com/bluetape4k/bluetape4k-leader/compare/v0.1.0-SNAPSHOT...HEAD
[0.1.0-SNAPSHOT]: https://github.com/bluetape4k/bluetape4k-leader/releases/tag/v0.1.0-SNAPSHOT
