# Changelog

All notable changes to `bluetape4k-leader` are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Planned

- `leader-exposed` — Exposed/JDBC backend (issue #7)
- `leader-mongodb` — MongoDB backend (issue #8)
- `leader-hazelcast` — Hazelcast backend (issue #9)
- `leader-micrometer` — Micrometer metrics integration (issue #10)
- `leader-spring-boot3` — Spring Boot 3 auto-configuration (issue #11)
- `leader-spring-boot4` — Spring Boot 4 auto-configuration (issue #12)
- CI/CD pipeline setup (issue #13)
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
