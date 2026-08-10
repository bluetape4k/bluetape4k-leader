# Issue #667 Exposed 그룹 선출 DB server time 구현 계획

- 승인된 명세: [`docs/superpowers/specs/2026-08-10-issue-667-exposed-group-db-time-design.md`](../specs/2026-08-10-issue-667-exposed-group-db-time-design.md)
- Issue: `#667`
- 기준 커밋: `6255a443`
- 작업 브랜치: `codex/issue-667-db-time`
- 대상 모듈: `bluetape4k-leader-core`, `bluetape4k-leader-exposed-jdbc`, `bluetape4k-leader-exposed-r2dbc`, EN/KO manual 및 README 진입점

## 목표와 중단 조건

`LeaderGroupElectionOptions.useDbTime`를 추가하고 Exposed JDBC/R2DBC 그룹 lock의 acquire,
active count, `isHeld`, min-lease release, extend가 단일 current-time primitive를 사용하도록
수정한다. `useDbTime=false`의 기존 동작과 기준 artifact의 실제 JVM constructor/copy descriptor를 보존하고,
`true`에서는 고정 SQL `SELECT CURRENT_TIMESTAMP`를 사용한다. H2/PostgreSQL/MySQL clock-skew
경합과 blocking/async/suspend 경로를 회귀 테스트로 증명하며 EN/KO 문서에 실제 wrapper 구성
경로를 제공한다.

다음 중 하나라도 해소되지 않으면 구현을 완료로 보고하지 않는다.

- 기준 0.5.0 artifact의 `javap -p -s` descriptor inventory와 old `copy-5t7Pxr8`/`$default`가 유지되지 않음
- DB timestamp 조회 실패가 JVM clock으로 조용히 fallback하거나 token predicate를 우회함
- `useDbTime=true`에서 timestamp 오류가 `activeCount`/cache fallback으로 admission을 열어 둠
- DB-time ownership transaction이 작업 대기 중 connection을 붙잡거나 query 예산을 초과함
- JDBC/R2DBC 중 하나의 ownership 경로 또는 cancellation cleanup이 검증되지 않음
- provider가 비활성인데 전체 H2/PostgreSQL/MySQL matrix PASS로 보고됨
- manual release pin을 임의로 바꾸거나 EN/KO wrapper 예제가 실제 package/import와 불일치함

## 비범위

- `#669`의 R2DBC wait deadline monotonic 전환
- `#668`의 publication POM license metadata
- group auto-extension API 추가
- manual `manifest.yaml`의 `releaseRef`/`releaseCommit` 변경, release dispatch, PR/merge

## 구현 순서 (TDD)

### 1. core API와 호환성 회귀 테스트를 먼저 추가

대상 파일:

- `leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderGroupElectionOptionsTest.kt`
- 새 `leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderGroupElectionOptionsCompatibilityTest.kt`
- 이후 구현: `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderGroupElectionOptions.kt`

작업:

1. 기본값 `false`, 명시적 `true`, `copy`, equality, validation을 검증하는 테스트를 추가한다.
2. Java serialization에서 기존 payload에 없는 Boolean이 `false`가 되고 새 `true` 값이 왕복되는지 검증한다.
3. 구현 전에 0.5.0 artifact와 기준 class를 `javap -p -s`로 inventory하고, Kotlin `Duration` mangling으로 관찰되는 private constructor `(IJJLjava/lang/String;J)V`, public `copy-5t7Pxr8`, static `$default`의 visibility/name/descriptor를 고정한다. 후보 data-class bridge는 `@JvmName`을 사용해 별도 ABI spike로 컴파일하고 reflection/descriptor 비교로 확인한다. Java caller 직접 호출은 mangled 이름 때문에 요구하지 않는다.
4. 기준 커밋의 frozen serialized bytes 또는 이전 artifact를 compatibility fixture로 고정하고 `ObjectStreamClass`의 `serialVersionUID`, constructor/copy/`$default` descriptor를 함께 검증한다.
5. 먼저 다음 명령을 실행해 새 테스트가 예상대로 실패하는지 확인한다.

```bash
./gradlew :bluetape4k-leader-core:test --tests 'io.bluetape4k.leader.LeaderGroupElectionOptionsTest'
./gradlew :bluetape4k-leader-core:test --tests 'io.bluetape4k.leader.LeaderGroupElectionOptionsCompatibilityTest'
```

6. `LeaderGroupElectionOptions` 마지막에 `useDbTime: Boolean = false`를 추가하고 한국어 KDoc을 갱신한다. ABI spike가 확인한 실제 descriptor에 맞춰 old private constructor secondary overload, `@JvmName("copy-5t7Pxr8")` old copy overload, companion의 `@JvmStatic @JvmName("copy-5t7Pxr8\$default")` bridge를 추가해 existing Kotlin/JVM descriptor를 유지한다. 새 6개 인자 copy와 `component6`/getter는 additive API로 둔다.
7. 같은 명령과 compatibility/serialization fixture를 재실행해 core 테스트를 녹색으로 만든다.

### 2. JDBC/R2DBC current-time primitive 추출

대상 파일:

- 새 `leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/lock/ExposedJdbcCurrentTime.kt`
- 새 `leader-exposed-r2dbc/src/main/kotlin/io/bluetape4k/leader/exposed/r2dbc/lock/ExposedR2dbcCurrentTime.kt`
- `leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/lock/ExposedJdbcLock.kt`
- `leader-exposed-r2dbc/src/main/kotlin/io/bluetape4k/leader/exposed/r2dbc/lock/ExposedR2dbcLock.kt`

작업:

1. 기존 single-lock의 `CURRENT_TIMESTAMP` 조회와 허용 반환형 변환을 adapter-local `internal` helper로 옮긴다. JDBC는 `Timestamp`, `Instant`, `OffsetDateTime`, `ZonedDateTime`, `LocalDateTime(UTC)`를 유지하고 R2DBC는 실제 driver 반환형을 테스트로 고정하며 필요 시 `Timestamp`를 추가한다.
2. helper는 `currentTime(useDbTime, clock)` 형태로 만들고 DB query 실패 시 예외를 보존한다. 사용자 SQL/dialect expression과 JVM fallback은 추가하지 않는다.
3. single lock은 helper를 호출하되 기본 `Clock.systemUTC()`를 사용한다. 기존 single-lock 테스트를 먼저 실행해 추출이 동작만 바꾸지 않았음을 확인한다.

추출 직후 회귀 명령은 `./gradlew :bluetape4k-leader-exposed-jdbc:test --tests '*LockTest'`와
`./gradlew :bluetape4k-leader-exposed-r2dbc:test --tests '*LockTest'`로 고정하고, 새
`*CurrentTimeTest` selector도 별도로 실행한다.

### 3. JDBC 그룹 lock/elector 구현과 테스트

대상 파일:

- `leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/lock/ExposedJdbcGroupLock.kt`
- `leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/ExposedJdbcLeaderGroupElector.kt`
- `leader-exposed-jdbc/src/test/kotlin/io/bluetape4k/leader/exposed/jdbc/lock/ExposedJdbcGroupLockTest.kt`
- `leader-exposed-jdbc/src/test/kotlin/io/bluetape4k/leader/exposed/jdbc/ExposedJdbcLeaderGroupElectionTest.kt`
- `leader-exposed-jdbc/src/test/kotlin/io/bluetape4k/leader/exposed/jdbc/AbstractExposedJdbcLeaderTest.kt` (필요 시 provider fixture만)
- `leader-exposed-core/src/test/kotlin/io/bluetape4k/leader/exposed/AbstractExposedTableTest.kt` (알 수 없는 provider selector fail-closed)

TDD 순서:

1. 기준 artifact descriptor와 기존 API 호출을 보존하는 constructor/copy 인자 테스트를 먼저 추가한다.
2. fixed `Clock`을 주입한 두 contender가 `useDbTime=false`에서 서로 다른 JVM 시계를 보이고, `true`에서 DB `CURRENT_TIMESTAMP` 기준으로 유효 row를 takeover하지 않는 회귀 테스트를 추가한다.
3. acquire, `isHeld`, min-release, extend, activeCount, `maxLeaders`에 대해 token predicate와 expired-token revival 방지를 assertion한다.
4. 고정된 test-only SQL recorder에서 total SQL count와 time-query count를 분리해 계약을 고정한다. DB-time acquire의 update/insert 양 branch, `isHeld`, `extend`, `activeCount`, min-release update는 transaction마다 `SELECT CURRENT_TIMESTAMP` 정확히 1회이고, delete release와 모든 local-time 경로는 0회여야 한다. 실패 후 다음 transaction이 다시 1회 조회하는 recovery도 포함한다.
5. H2 fast suite에서 bounded pool보다 많은 contender, 짧은 wait/deadline, bounded latch를 사용해 wait가 transaction 밖이고 connection 반환/leak가 없음을 고정한다. provider smoke는 별도 exact test name/tag로 분리한다.
6. 테스트가 실패하는 것을 확인한 뒤 group lock constructor 마지막에 `useDbTime`과 internal `Clock`을 추가하고 모든 ownership 시간 계산을 helper로 치환한다. sync/async elector의 lock 생성과 JDBC `activeCount`에 옵션을 전달한다.
7. `useDbTime=true` timestamp 오류에서 `activeCount`가 `maxLeaders`를 반환하도록 하여 `availableSlots == 0`인 보수적 상태를 만들고, fault-injection admission-block 및 다음 transaction recovery를 확인한다. `false` 모드의 기존 0/fallback 계약은 보존한다.
8. min-release update는 current time을 한 번 조회하고 `token AND lockedUntil > now` predicate를 사용해 만료된 동일-token row를 부활시키지 않는지 검증한다. 기존 JDBC contention/null/false/extend/unlock 오류 계약을 유지하되 timestamp 조회에서 JVM fallback은 금지한다.

실행 명령:

```bash
LEADER_TEST_DB=H2 ./gradlew :bluetape4k-leader-exposed-jdbc:test --tests '*Group*Test'
```

### 4. R2DBC 그룹 lock/elector 구현과 테스트

대상 파일:

- `leader-exposed-r2dbc/src/main/kotlin/io/bluetape4k/leader/exposed/r2dbc/lock/ExposedR2dbcGroupLock.kt`
- `leader-exposed-r2dbc/src/main/kotlin/io/bluetape4k/leader/exposed/r2dbc/ExposedR2DbcSuspendLeaderGroupElector.kt`
- `leader-exposed-r2dbc/src/test/kotlin/io/bluetape4k/leader/exposed/r2dbc/lock/ExposedR2dbcGroupLockTest.kt`
- `leader-exposed-r2dbc/src/test/kotlin/io/bluetape4k/leader/exposed/r2dbc/ExposedR2DbcSuspendLeaderGroupElectorTest.kt`
- `leader-exposed-r2dbc/src/test/kotlin/io/bluetape4k/leader/exposed/r2dbc/lock/R2dbcLockCancellationTest.kt` (공통 취소 helper만 필요한 경우)
- `leader-exposed-r2dbc/src/test/kotlin/io/bluetape4k/leader/exposed/r2dbc/AbstractExposedR2dbcLeaderTest.kt` (알 수 없는 provider selector fail-closed)

TDD 순서:

1. JDBC와 동일한 fixed-clock, DB-time, activeCountSuspend, maxLeaders, token/expired-row 회귀를 suspend transaction 테스트로 먼저 추가한다. 실제 driver 반환형 변환, exact time-query budget, 실패 후 recovery도 R2DBC에 별도로 고정한다.
2. 명시적 bounded R2DBC pool에서 pool보다 많은 contender, 짧은 wait/deadline, connection 반환/leak assertion을 실행한다. acquire/action 중 `Job.cancel`에서 `CancellationException` 재전파, `NonCancellable` cleanup, row/token 정리, cache decrement, 다음 재획득을 검증한다.
3. lock 획득 직후 cleanup guard를 열고 `recordAcquired`/history/watchdog 초기화까지 nested `finally`로 감싸 setup 중 cancellation이나 history/watchdog 실패에도 unlock이 실행되도록 한다.
4. 테스트가 실패하는 것을 확인한 뒤 group lock에 `useDbTime`/internal `Clock`을 전달하고 `activeCountSuspend`와 모든 ownership 경로를 helper로 치환한다. lockName별 `ConcurrentHashMap` unavailable set을 원자적으로 추가한다. `useDbTime=true` timestamp 오류는 set에 등록하고 `maxLeaders`를 반환하여 `activeCount`/`availableSlots`/`state`를 fail-closed한다. `activeCount()`는 unavailable 동안 같은 보수 값을 노출하고, 성공한 DB refresh에서 set을 제거한다. `runIfLeader`는 stale cache로 조기 차단하지 않고 tryLock 오류를 `null`로 전파한다. `false` 모드와 기존 cache-only 의미는 manual에 명시한다.
5. R2DBC retry deadline의 wall-clock 구현은 수정하지 않는다.

실행 명령:

```bash
LEADER_TEST_DB=H2 ./gradlew :bluetape4k-leader-exposed-r2dbc:test --tests '*Group*Test'
```

### 5. EN/KO manual과 README 진입점

대상 파일:

- `docs/manual/en/backends/exposed-sql.md`
- `docs/manual/ko/backends/exposed-sql.md`
- `README.md`, `README.ko.md`
- `leader-core/README.md`, `leader-core/README.ko.md`
- `leader-exposed-jdbc/README.md`, `leader-exposed-jdbc/README.ko.md`
- `leader-exposed-r2dbc/README.md`, `leader-exposed-r2dbc/README.ko.md`

작업:

1. EN/KO manual에 `0.6.0+` 적용 버전, `useDbTime=true`의 fixed SQL 의미, 실제 JDBC/R2DBC wrapper/import/factory 예제, blocking/async/suspend 범위, authoritative DB와 routing/failover, timezone/precision, query/pool 비용, history/cache-only activeCount의 비권위 의미를 같은 구조로 추가한다.
2. 현재 `releaseRef: 0.5.0` 및 `releaseCommit`은 변경하지 않는다. release pin 변경은 별도 publish gate이다.
3. 모든 README locale snippet에 한 줄 설명과 manual 링크를 추가하고 root README의 잘못된 `io.bluetape4k.leader.core` import를 `io.bluetape4k.leader`로 고친다.
4. 코드 fence의 package/import/생성자 구문을 실제 source와 대조하고 EN/KO heading/link parity를 확인한다.
5. provenance matrix를 `0.5.0 pinned manual` 대 `0.6.0+ develop API`로 명시하고, JDBC `Database.connect(dataSource)`/wrapper, R2DBC `suspend fun`/factory, JDBC blocking/async·R2DBC suspend·SPI factory 예제를 각각 compile-check한다. root/core README에는 Exposed만 지원하고 Redis 등에서는 `useDbTime`을 켜지 않는다는 경고를 둔다.

### 6. 전체 검증과 issue DoD 증거

provider별 테스트는 병렬 실행하지 않고 순차 실행한다. `LEADER_TEST_DB`가 선택한 provider만 실행하므로 세 provider 명령의 실제 결과를 각각 기록한다. 알 수 없는 selector는 두 test fixture에서 즉시 오류로 fail-closed하며 전체 matrix로 조용히 확장하지 않는다. 각 command는 `--no-daemon --console=plain --no-configuration-cache`와 900초 harness timeout을 사용하고 stdout/stderr, duration, exit status를 캡처한다. H2 deterministic suite와 provider smoke의 test name/tag, contender 수, wait/latch timeout, connection-pool 크기와 readiness/parameter count를 함께 남긴다.

```bash
./gradlew --no-daemon --console=plain --no-configuration-cache :bluetape4k-leader-core:test --tests 'io.bluetape4k.leader.LeaderGroupElectionOptionsTest' --tests 'io.bluetape4k.leader.LeaderGroupElectionOptionsCompatibilityTest'
./gradlew --no-daemon --console=plain --no-configuration-cache :bluetape4k-leader-exposed-jdbc:test --tests '*LockTest' --tests '*CurrentTimeTest'
./gradlew --no-daemon --console=plain --no-configuration-cache :bluetape4k-leader-exposed-r2dbc:test --tests '*LockTest' --tests '*CurrentTimeTest'
LEADER_TEST_DB=H2 ./gradlew --no-daemon --console=plain --no-configuration-cache :bluetape4k-leader-exposed-jdbc:test --tests '*Group*Test'
LEADER_TEST_DB=POSTGRESQL ./gradlew --no-daemon --console=plain --no-configuration-cache :bluetape4k-leader-exposed-jdbc:test --tests '*Group*Test'
LEADER_TEST_DB=MYSQL_V8 ./gradlew --no-daemon --console=plain --no-configuration-cache :bluetape4k-leader-exposed-jdbc:test --tests '*Group*Test'
LEADER_TEST_DB=H2 ./gradlew --no-daemon --console=plain --no-configuration-cache :bluetape4k-leader-exposed-r2dbc:test --tests '*Group*Test'
LEADER_TEST_DB=POSTGRESQL ./gradlew --no-daemon --console=plain --no-configuration-cache :bluetape4k-leader-exposed-r2dbc:test --tests '*Group*Test'
LEADER_TEST_DB=MYSQL_V8 ./gradlew --no-daemon --console=plain --no-configuration-cache :bluetape4k-leader-exposed-r2dbc:test --tests '*Group*Test'
./gradlew --no-daemon --console=plain --no-configuration-cache :bluetape4k-leader-core:detekt :bluetape4k-leader-exposed-jdbc:detekt :bluetape4k-leader-exposed-r2dbc:detekt
ABI_BASE_VERSION=0.5.0 ABI_CURRENT_VERSION=0.6.0 ./gradlew --no-daemon --console=plain --no-configuration-cache checkBinaryCompatibility
./gradlew --no-daemon --console=plain --no-configuration-cache exportManualModuleInventory
ruby scripts/manual/release_inventory.rb 0.5.0 721a9a3808f67489d2bdb8177734325981c24977 build/manual/module-inventory.json build/manual/release-module-inventory.json 35
ruby scripts/manual/validate_manuals.rb build/manual/release-module-inventory.json
ruby scripts/manual/validate_release_manuals.rb 0.5.0 721a9a3808f67489d2bdb8177734325981c24977
ruby scripts/manual/export_manifest.rb --check
git diff --check
```

provider가 환경상 비활성인 경우 실제 출력, 원인, 대체 검증을 `build/issue-667-evidence/<provider>-<module>.json`에 기록하고 PASS가 아닌 `PENDING`으로 남긴다. 각 JSON에는 command, selector, test names, pool/readiness, duration, exit status, JUnit XML 경로, disabled reason, SHA-256 manifest를 포함한다. 세 Issue #667 DoD checkbox(ABI/ownership correctness, JDBC/R2DBC parity, manual/provenance)를 artifact field로 매핑한다. 기존 release pin이 새 API를 포함하지 않는 provenance gap은 `PENDING`으로 남기고 release 작업으로 넘긴다.

### 롤아웃과 롤백

기본값 `useDbTime=false`로 배포하고, 동일 authoritative DB를 공유하는 caller만 provider matrix와
admission-unavailable 회귀가 PASS된 뒤 단계적으로 `true`를 활성화한다. DB-time 오류가
`activeCount == maxLeaders`, `availableSlots == 0`, try-lock `null`로 관측되거나 query budget가
초과되면 해당 caller의 flag를 즉시 `false`로 되돌리고 이전 artifact로 rollback한다. 이 변경은
schema migration을 만들지 않으므로 schema rollback은 필요하지 않다. rollback과 재검증은
동일한 evidence artifact에 기록한다.

## 계획 검토 및 실행 게이트

이 문서를 작성한 뒤 performance, stability, security, operations, API, user-facing documentation 6관점의 read-only plan review를 두 wave로 실행한다. 이번 review에서 확인한 ABI spike, exact query budget, fail-closed active-count, stale-token 방지, R2DBC setup cleanup, bounded pool/cancellation, provider evidence P1은 모두 계획과 명세에 반영한 뒤에만 TDD 구현을 시작한다. 계획 검토가 완료되면 승인된 명세와 이 계획에 맞춰 테스트 → 구현 → 검증 순서로 진행한다.

## 완료 산출물

- 구현 및 회귀 테스트 변경
- EN/KO manual·README 변경
- provider별 테스트/정적분석/ABI/manual 검증 결과
- `.bluetape` workflow evidence와 Type-A lesson artifact
- Issue #667 DoD에 연결할 Korean progress/result evidence (PR/merge는 별도 승인 게이트)
