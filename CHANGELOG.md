# Changelog

All notable changes to `bluetape4k-leader` are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added

- **`leader-core`**: ShedLock-호환 ergonomic API + reentrant + 명시적 lease 연장 (issue #79 PR 1 Core)
  - `LockAssert` — `assertLocked()` / `assertLocked(lockName)` / `isLocked()` 그리고 suspend 변형
    (`assertLockedSuspend` / `isLockedSuspend`)
  - `LockExtender` — `extendActiveLock(Duration): Boolean` + Java `java.time.Duration` overload
    + `extendActiveLockDetailed(Duration): ExtendOutcome` (sealed result) + suspend 변형
  - `LeaderLockHandle` sealed class (`Real` / `FailOpen`) — `internal constructor` + companion factory
  - `LockIdentity` — `(lockName, kind, factoryBeanName, groupParams)` 4-tuple. `equals/hashCode` 에서
    `factoryBeanName` 제외 — sync ↔ suspend nested 호출 시에도 reentrant 정확 (Step 3-P R3)
  - `ExtendOutcome` sealed — `Extended` / `NotHeld` / `WrongThread` / `BackendError(Exception)`
  - `LockHandleElement` — `CoroutineContext.Element` (internal handle, public Key)
  - `BackendErrorClassifier` SPI + `CoreBackendErrorClassifier` (JDK/공통) + `CompositeBackendErrorClassifier`
  - `runIfLeaderResultSuspend` default fun on `SuspendLeaderElector` / `SuspendLeaderGroupElector`
    (binary-compat via `-jvm-default=enable`)
  - `LeaderLeaseAutoExtender.start(delegate: ExtendDelegate)` 신규 시그니처. 기존 `(Duration) -> Boolean`
    `@Deprecated`. `ExtendDelegate.lastExtendDeadline` 으로 watchdog × user-extend last-write-wins
    metric 가시화 (Step 3-P R2)
  - Local elector (sync / suspend / group / suspend-group) — `CaptureScope` + `LeaderLockHandle` 통합

- **`leader-spring-boot`**: `@LeaderElection` / `@LeaderGroupElection` aspect 의 `LockAssert` / `LockExtender` 통합
  (issue #79 PR 1)
  - sync / suspend / Mono 3 branch — reentrant peek (Real handle) + sentinel push (FAIL_OPEN_RUN)
    + `BodyThrownMarker` (body exception 보호) + `CaptureInvariantException` (spec invariant fail-fast)
  - `AdviceBranch { SYNC, COROUTINES, REACTIVE }` enum (카테고리 명칭 — Flow / Flux 등 향후 확장 여지)
  - `AdviceMetadata.annotationKind` explicit field + `resolveLockIdentity(branch)`
  - `LeaderAnnotationValidatorBeanPostProcessor` — `CompletableFuture` / `Future` / `ListenableFuture`
    / `kotlinx.coroutines.Deferred` 반환 타입 차단 (Step 3-P R12 — lock release 가 future 완료 전 발생 위험)
  - Kotlin reified `findMergedAnnotationOrNull<A>()` / `hasMergedAnnotation<A>()` extension
    (`AnnotationExt.kt`, idiom 적용)

### Changed

- `LeaderElectionAspect` / `LeaderGroupElectionAspect` — outer `catch (Exception)` (Throwable 아님 —
  `OutOfMemoryError` / `StackOverflowError` / `LinkageError` 차단)

- **`examples/`**: Runnable example modules demonstrating production scenarios for `bluetape4k-leader` (issue #36)
  - `examples/batch-scheduler` — Lettuce Redis 기반 분산 batch 스케줄러; 야간 정산 등 주기 작업의
    멀티 인스턴스 단일 실행 보장 (PR #159)
  - `examples/migration-gate` — Exposed JDBC 기반 마이그레이션 게이트; PostgreSQL/H2에서
    부팅 시 1개 인스턴스만 schema migration 수행 (PR #160)
  - `examples/webhook-poller` — MongoDB 기반 webhook poller; `findOneAndUpdate` + TTL index
    리더 선출로 단일 인스턴스만 외부 webhook 폴링 (PR #161)
  - `examples/cache-warmer` — Hazelcast 기반 partition 별 독립 leader-election 캐시 워머;
    파티션별 lockName 으로 "partition P 당 정확히 1 인스턴스" 계약 보장 (PR #162)
  - `examples/tenant-aggregator` — Exposed R2DBC 기반 코루틴 네이티브 멀티 테넌트 집계기;
    테넌트별 독립 leader-election + suspend 처리 (PR #163)
  - `examples/ktor-app` — Ktor 3.x + Lettuce Redis 통합 예제; `LeaderElectionPlugin` +
    `leaderScheduled()` 사용 (PR #166)
  - 모든 examples 모듈은 publishing 대상에서 자동 제외 (`path.startsWith(":examples:")` 가드)

- **`leader-ktor`**: Ktor 3.x 통합 모듈 (issue #37, PR #164)
  - `LeaderElectionPlugin` — `createApplicationPlugin` DSL, `SuspendLeaderElector` 기반
  - `Application.leaderScheduled(lockName, period) { ... }` — Spring `@Scheduled` 스타일
    리더 전용 주기 작업 헬퍼; `Application` 코루틴 스코프에서 launch 되어
    `ApplicationStopped` 시 자동 취소, action 예외는 cycle 격리 후 다음 cycle 진행
  - `Application.leaderElectionPluginConfig()` — 설치된 플러그인 설정 조회 확장
  - Testcontainers Redis 기반 통합 테스트 (`testApplication` DSL + Redisson 백엔드)
  - Ktor 버전: 3.4.3
  - `leader-bom` 에 등록되어 BOM consumer 가 버전 명시 없이 사용 가능
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

- **`leader-exposed-core`**: Exposed 공통 스키마 정의 (issue #23)
  - `LeaderLockTable`, `LeaderGroupLockTable` — JDBC/R2DBC 양쪽 모듈이 공유하는 Exposed Table DDL

- **`leader-exposed-jdbc`**: Exposed + JDBC blocking 분산 락 백엔드 (issue #21, PR #52)
  - `ExposedJdbcLock` — INSERT UPSERT + token 기반 소유자 전용 해제 (H2/PostgreSQL/MySQL)
  - `ExposedJdbcGroupLock` — `(lockName, slot)` composite PK 슬롯 기반 그룹 락
  - `ExposedJdbcLeaderElection` — blocking 단일 리더 선출 (sync + CompletableFuture async)
  - `ExposedJdbcLeaderGroupElection` — blocking 복수 리더 선출
  - `isHeldByCurrentInstance()` — token + `lockedUntil > NOW()` 기반 소유권 확인
  - H2/PostgreSQL/MySQL Testcontainers 통합 테스트 (196 테스트 통과)

- **`leader-exposed-r2dbc`**: Exposed + R2DBC coroutine 분산 락 백엔드 (issue #22, PR #62)
  - `ExposedR2dbcSuspendLeaderElection` — coroutine 단일 리더 선출
  - `ExposedR2dbcSuspendLeaderGroupElection` — coroutine 복수 리더 선출
  - R2DBC PostgreSQL Testcontainers 통합 테스트

- **CI/CD**: GitHub Actions 파이프라인 (issue #13, #35, PR #19, #20, #44)
  - Build(compile-only), Test(core/lettuce/redisson), Secret Scan(gitleaks), Validate Gradle Wrapper
  - Nightly SNAPSHOT 자동 publish (테스트 성공 시에만)
  - `leader-redis-lettuce`, `leader-redis-redisson` 병렬 테스트 잡 추가 (PR #44)

- **문서**: README.md / README.ko.md 전 모듈 작성 (issue #14, PR #18)
  - 루트, leader-core, leader-redis-lettuce, leader-redis-redisson, leader-hazelcast
  - ShedLock 비교 문서, Spring Boot / Ktor 통합 자료조사 문서

- **`leader-zookeeper`**: ZooKeeper/Curator 기반 리더 선출 백엔드를 추가했습니다 ([PR #138](https://github.com/bluetape4k/bluetape4k-leader/pull/138)).

- **Instrumented leader electors**: 리더 선출 동작을 계측 가능한 electors로 감싸는 경로를 추가했습니다 ([PR #136](https://github.com/bluetape4k/bluetape4k-leader/pull/136)).

- **`leader-core` lifecycle listeners**: `LeaderElectionListener`, listener-aware decorators, and suspend event stream backed by `PublishSubject` internally (issue #40, PR #146).

- **`leader-micrometer` listener counters**: `MicrometerLeaderElectionListener` records `leader.election.events` with `lock.name` and `event` tags (issue #40, PR #146).

- **`leader-bom` README**: BOM usage documentation was added in English and Korean ([PR #141](https://github.com/bluetape4k/bluetape4k-leader/pull/141)).

### Fixed

- **Coroutine cancellation safety**: 전 코루틴 백엔드(Lettuce, Redisson, Hazelcast, MongoDB)에서
  unlock/release를 `withContext(NonCancellable)`로 보호. 취소 시 락 누수 방지 (PR #25, daily review 2026-05-01)
- **`CancellationException` rethrow**: `catch(Exception)` 블록 앞에 `catch(CancellationException) { throw e }` 추가.
  `withContext(NonCancellable)` 안에서도 필수 (daily review 2026-05-01, PR #45)
- **Lettuce 관찰성**: `runCatching { unlock/release }` 실패 시 `.onFailure { log.warn }` 으로 기록.
  이전에는 토큰 불일치/Redis 오류가 무시됨 (daily review)
- **Kover 모듈별 coverage 집계 스크립트** 버그 수정 — 결과 집계 누락 문제
- **deprecated API 교체**: `TimebasedUuid.Epoch` → `Uuid.V7` (Kotlin 2.3+ 대응)
- **`ExposedJdbcGroupLock.isHeldByCurrentInstance()`**: token + `lockedUntil > NOW()` 체크 누락 메서드 추가 (issue #59, PR #63)
- **`ExposedJdbcGroupLock.tryLock()` DB 오류 전파**: `Boolean?` tri-state 반환으로 DB 오류와 슬롯 경합 실패 구분 가능 (issue #60, PR #63)
- **Exposed Lock 클래스 `KLoggingChannel` 이식**: `KLogging` → `KLoggingChannel` (coroutine 컨텍스트 로거) (issue #61, PR #63)
- **`ExposedR2dbcGroupLock.tryLock()` DB 오류 전파**: `Boolean?` tri-state 반환으로 JDBC 형제 모듈과 contract 통일 (daily review 2026-05-04)
- **Exposed Lock SELECT 술어 강화**: JDBC `tryAcquireOnce` Step 3 SELECT에 `lockedUntil > NOW()` 추가 — split-brain 방지 + R2DBC 대칭 (daily review 2026-05-04)
- **leader-bom publishing**: NMCP aggregation and Central Snapshots publishing were fixed for `leader-bom` ([PR #140](https://github.com/bluetape4k/bluetape4k-leader/pull/140)).
- **factory failure handling**: `factory.create()` I/O failures now respect the configured failure mode instead of bypassing it ([PR #107](https://github.com/bluetape4k/bluetape4k-leader/pull/107)).
- **LeaderAopProperties binding**: `@ConfigurationProperties` was added where required for AOP properties binding ([PR #93](https://github.com/bluetape4k/bluetape4k-leader/pull/93)).

### Changed

- **Argument validation**: `LeaderElectionOptions`, `LeaderGroupElectionOptions`, `LeaderGroupState`
  가 `init {}` 블록에서 즉시 검증 (`waitTime` ≥ 0, `leaseTime` > 0, `maxLeaders` ≥ 1) (PR #25)
- **Redisson KDoc 정확성**: 잘못된 `@throws RedisException on contention` 제거,
  실제 계약(`null` 반환)으로 교체 (daily review)
- **Suspend 인터페이스 계약**: `SuspendLeaderElection`, `SuspendLeaderGroupElection` KDoc에
  취소 계약 명시 — 구현체는 락/슬롯 해제 후 `CancellationException` 재throw 필수 (daily review)
- **`validateLockName` 중복 제거**: 6개 파일의 `private fun` → `internal fun` (MongoLock.kt) 단일 추출
- **`isHeldByCurrentInstance()` 제거** (MongoDB): 불필요한 pre-check DB 왕복 제거. 토큰 기반 `deleteOne`이 이미 안전
- **`ExposedJdbcGroupLock.tryLock()` 반환 타입 `Boolean → Boolean?`**: `null` = DB 오류(순회 중단 신호), `false` = 슬롯 경합, `true` = 획득 성공 (issue #60, PR #63)
- **Suspend 테스트 `runTest` → `runSuspendIO`**: 실제 IO(MongoDB/Testcontainers) 테스트는
  virtual time 대신 `runBlocking(Dispatchers.IO)` 사용
- Spring Boot 3/4 split was removed from leader Spring integration: `leader-spring-boot-common`, `leader-spring-boot3`, and `leader-spring-boot4` were consolidated into a single `leader-spring-boot` module ([PR #105](https://github.com/bluetape4k/bluetape4k-leader/pull/105)).
- `LeaderElection` / `LeaderGroupElection` interfaces were renamed to `LeaderElector` / `LeaderGroupElector` and documentation was updated accordingly ([PR #106](https://github.com/bluetape4k/bluetape4k-leader/pull/106), [PR #123](https://github.com/bluetape4k/bluetape4k-leader/pull/123), [PR #125](https://github.com/bluetape4k/bluetape4k-leader/pull/125)).
- Duration APIs migrated from `java.time.Duration` to `kotlin.time.Duration` ([PR #126](https://github.com/bluetape4k/bluetape4k-leader/pull/126)).
- Test code migrated from JUnit `assertThrows` and bluetape4k-assertions patterns to Kotlin/assertions-friendly APIs ([PR #131](https://github.com/bluetape4k/bluetape4k-leader/pull/131), [PR #139](https://github.com/bluetape4k/bluetape4k-leader/pull/139)).
- CI uses paths-filter and retry configuration to reduce unnecessary test work and transient failure noise ([PR #135](https://github.com/bluetape4k/bluetape4k-leader/pull/135)).
- Prometheus export coverage now proves AOP and direct elector metrics through `PrometheusServer` scrape tests ([PR #144](https://github.com/bluetape4k/bluetape4k-leader/pull/144)).

### Open Follow-ups

- watchdog / lease auto-extend for long-running leader AOP work (issue #73)
- `minLeaseTime` backend TTL delegation and `lockAtLeastFor` semantics (issue #77)
- `LockExtender` / `LockAssert` style explicit lease extension API (issue #79)
- Flux/Flow AOP return type support after lease renewal semantics are settled (issue #74)
- Election state API, audit contract, multitenancy, and Prometheus runnable example (issues #68, #50, #42, #145)

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
