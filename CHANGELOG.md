# Changelog

All notable changes to `bluetape4k-leader` are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added

- **`leader-mongodb`**: MongoDB `findOneAndUpdate` + TTL index 기반 분산 락 백엔드 (issue #8, PR #46)
  - `MongoLock` — sync blocking, `findOneAndUpdate` upsert + `deleteOne(token)` 소유자 전용 해제
  - `MongoSuspendLock` — Kotlin coroutine driver 기반 suspend 분산 락
  - `MongoLeaderElection` — blocking 단일 리더 선출 (sync + `CompletableFuture` async)
  - `MongoSuspendLeaderElection` — coroutine 단일 리더 선출
  - `MongoLeaderGroupElection` — blocking 복수 리더 선출 (`lockName:slot:N` 슬롯 기반)
  - `MongoSuspendLeaderGroupElection` — coroutine 복수 리더 선출 (이중 컬렉션 설계)
  - 라인 커버리지 82.4% (42 테스트), Testcontainers MongoDB 통합 테스트

- **`leader-hazelcast`**: Hazelcast `IMap` 토큰 락 기반 분산 리더 선출 (issue #9)
  - `HazelcastLeaderElection` — blocking 단일 리더 선출
  - `HazelcastLeaderGroupElection` — blocking 복수 리더 선출
  - `HazelcastSuspendLeaderElection` — coroutine 단일 리더 선출
  - `HazelcastSuspendLeaderGroupElection` — coroutine 복수 리더 선출
  - `HazelcastLock`, `HazelcastSuspendLock` — IMap 기반 토큰 분산 락

- **`leader-spring-boot-common`**: Boot 버전 독립 공통 모듈 (issue #27, PR #28)
  - `LeaderElectionProperties` — `leader.*` 설정 프로퍼티 data class
  - `AbstractLeaderElectionAutoConfiguration` — Boot 3/4 공통 자동설정 기반 클래스

- **`StrategicLeaderElection`**: 플러그형 선출 전략 (issue #29, #31, #32)
  - `CandidateInfo`, `ElectionStrategy`, `CandidateScorer` 인터페이스
  - 내장 전략: `FifoElectionStrategy`, `RandomElectionStrategy`, `ScoredElectionStrategy`
  - 내장 Scorer: `IdleTimeScorer`, `SuccessRateScorer`, `RecentSuccessScorer`, `WeightedScorer`
  - Redis 백엔드 `CandidateRegistry` (Redisson sorted set/hash + TTL, Lettuce 버전 포함)

- **`leader-exposed-core/jdbc/r2dbc`**: 모듈 구조 생성 (issue #7, refactor #24)
  - `leader-exposed-core`, `leader-exposed-jdbc`, `leader-exposed-r2dbc` 3개 모듈 분리
  - 실제 구현은 별도 이슈 (#21, #22, #23) 로 관리

- **CI/CD**: GitHub Actions 파이프라인 (issue #13, #35, PR #19, #20, #44)
  - Build(compile-only), Test(core/lettuce/redisson), Secret Scan(gitleaks), Validate Gradle Wrapper
  - Nightly SNAPSHOT 자동 publish (테스트 성공 시에만)
  - `leader-redis-lettuce`, `leader-redis-redisson` 병렬 테스트 잡 추가 (PR #44)

- **문서**: README.md / README.ko.md 전 모듈 작성 (issue #14, PR #18)
  - 루트, leader-core, leader-redis-lettuce, leader-redis-redisson, leader-hazelcast
  - ShedLock 비교 문서, Spring Boot / Ktor 통합 자료조사 문서

### Fixed

- **Coroutine cancellation safety**: 전 코루틴 백엔드(Lettuce, Redisson, Hazelcast, MongoDB)에서
  unlock/release를 `withContext(NonCancellable)`로 보호. 취소 시 락 누수 방지 (PR #25, daily review 2026-05-01)
- **`CancellationException` rethrow**: `catch(Exception)` 블록 앞에 `catch(CancellationException) { throw e }` 추가.
  `withContext(NonCancellable)` 안에서도 필수 (daily review 2026-05-01, PR #45)
- **Lettuce 관찰성**: `runCatching { unlock/release }` 실패 시 `.onFailure { log.warn }` 으로 기록.
  이전에는 토큰 불일치/Redis 오류가 무시됨 (daily review)
- **Kover 모듈별 coverage 집계 스크립트** 버그 수정 — 결과 집계 누락 문제
- **deprecated API 교체**: `TimebasedUuid.Epoch` → `Uuid.V7` (Kotlin 2.3+ 대응)

### Changed

- **Argument validation**: `LeaderElectionOptions`, `LeaderGroupElectionOptions`, `LeaderGroupState`
  가 `init {}` 블록에서 즉시 검증 (`waitTime` ≥ 0, `leaseTime` > 0, `maxLeaders` ≥ 1) (PR #25)
- **Redisson KDoc 정확성**: 잘못된 `@throws RedisException on contention` 제거,
  실제 계약(`null` 반환)으로 교체 (daily review)
- **Suspend 인터페이스 계약**: `SuspendLeaderElection`, `SuspendLeaderGroupElection` KDoc에
  취소 계약 명시 — 구현체는 락/슬롯 해제 후 `CancellationException` 재throw 필수 (daily review)
- **`validateLockName` 중복 제거**: 6개 파일의 `private fun` → `internal fun` (MongoLock.kt) 단일 추출
- **`isHeldByCurrentInstance()` 제거**: 불필요한 pre-check DB 왕복 제거. 토큰 기반 `deleteOne`이 이미 안전
- **Suspend 테스트 `runTest` → `runSuspendIO`**: 실제 IO(MongoDB/Testcontainers) 테스트는
  virtual time 대신 `runBlocking(Dispatchers.IO)` 사용

### Planned

- `leader-exposed-jdbc` — Exposed + JDBC blocking implementation (issue #21)
- `leader-exposed-r2dbc` — Exposed + R2DBC coroutine implementation (issue #22)
- `leader-exposed-core` — 공통 DB 스키마 정의 (issue #23)
- `leader-hazelcast` FencedLock 버전 (issue #33)
- `leader-zookeeper` — ZooKeeper/Curator backend (issue #34)
- `leader-micrometer` — Micrometer metrics integration (issue #10)
- `leader-spring-boot3` — Spring Boot 3 auto-configuration (issue #11)
- `leader-spring-boot4` — Spring Boot 4 auto-configuration (issue #12)
- `lockAtLeastFor` (`minLeaseTime`) 지원 (issue #38)
- `LeaderElectionListener` (`onElected` / `onRevoked`) (issue #40)
- `@Leader` AOP 애노테이션 (issue #41)
- 멀티테넌시 — 테넌트별 락 네임스페이스 (issue #42)

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
