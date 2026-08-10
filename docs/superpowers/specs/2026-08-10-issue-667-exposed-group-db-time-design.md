# 설계 명세 - Issue #667 Exposed 그룹 선출 DB server time 정책

- **Issue**: #667 - `bug(exposed): 그룹 선출에 DB server time 정책을 추가해 clock parity 보장`
- **Date**: 2026-08-10
- **Branch**: `codex/issue-667-db-time`
- **Worktree**: `.worktrees/issue-667-db-time`
- **대상 모듈**: `leader-core`, `leader-exposed-jdbc`, `leader-exposed-r2dbc`, EN/KO Exposed SQL manual

## 1. 문제와 목표

`LeaderElectionOptions`에는 `useDbTime`이 있어 단일 lock의 lease 만료와 ownership 판정을
database server clock으로 통일할 수 있다. 반면 `LeaderGroupElectionOptions`에는 같은 정책이
없고 Exposed JDBC/R2DBC 그룹 lock과 active-count 경로가 `Instant.now()`를 직접 호출한다.
노드의 JVM clock이 서로 다르면 아직 유효한 slot을 만료된 것으로 판단하거나, 만료된 token을
잘못 되살려 `maxLeaders`와 token ownership 계약을 훼손할 수 있다.

목표는 다음과 같다.

1. 그룹 API에도 single API와 대칭인 DB server time 선택 정책을 제공한다.
2. JDBC/R2DBC 그룹 acquire, active count, `isHeld`, min-lease release, extend가 같은
   current-time primitive와 같은 정책을 사용하게 한다.
3. 기본 동작과 기존 생성자 호출을 보존하면서 clock offset 회귀를 고정한다.
4. EN/KO Exposed SQL manual이 실제 구성 경로와 blocking/suspend 의미를 설명하게 한다.

## 2. 현재 근거

| 위치 | 관찰 |
|---|---|
| `leader-core/.../LeaderElectionOptions.kt` | `useDbTime: Boolean = false`가 있고 단일 선출에 전달된다. |
| `leader-core/.../LeaderGroupElectionOptions.kt` | DB time 정책이 없다. |
| `leader-exposed-jdbc/.../ExposedJdbcLock.kt` | `JdbcTransaction.currentTime()`가 `SELECT CURRENT_TIMESTAMP`와 `Instant.now()`를 선택한다. |
| `leader-exposed-r2dbc/.../ExposedR2dbcLock.kt` | `R2dbcTransaction.currentTime()`가 같은 선택을 수행한다. |
| `ExposedJdbcGroupLock` / `ExposedR2dbcGroupLock` | acquire, `isHeld`, release, extend가 JVM clock을 직접 사용한다. |
| `ExposedJdbcLeaderGroupElector` / `ExposedR2DbcSuspendLeaderGroupElector` | active-count DB predicate가 JVM clock을 사용한다. |
| `docs/manual/{en,ko}/backends/exposed-sql.md` | 시계 일관성이 중요하면 DB time을 권장하지만 그룹 API의 설정 경로는 설명하지 않는다. |

이 작업에서 event history의 `startedAt`, `finishedAt`, `recordAcquired`는 lease ownership
판정이 아닌 관측 metadata이므로 process event timestamp로 유지한다. `lockedUntil`/DB row와
history timestamp가 JVM clock skew에서 다를 수 있으므로 history를 capacity/ownership의
authoritative source로 사용하지 않는다는 KDoc과 manual 문구를 추가한다. R2DBC의 wall-clock
wait deadline은 별도 #669 범위이므로 이번 변경에서 건드리지 않는다.

## 3. 접근 방법 비교

### 접근 A - 공통 core 옵션과 current-time primitive 추출 (선택)

`LeaderGroupElectionOptions` 끝에 `useDbTime: Boolean = false`를 추가한다. 기존 single lock의
JDBC/R2DBC DB timestamp 변환 로직을 각 adapter의 `internal` helper 파일로 추출하고, single/group
lock과 active-count가 이 helper를 호출한다. 그룹 lock 생성자는 내부 테스트용 `Clock` seam을
마지막 optional 인자로 받아 JVM offset을 결정적으로 재현한다.

- 장점: single/group API 의미가 대칭이고 정책이 한 곳에서 전달된다.
- 장점: JDBC와 R2DBC가 동일한 conversion/선택 규칙을 재사용한다.
- 단점: core public data class constructor가 확장되고 adapter 내부 파일이 추가된다.

### 접근 B - Exposed JDBC/R2DBC 옵션에만 `useDbTime` 추가

각 adapter의 `Exposed*LeaderGroupElectionOptions`에 독립 boolean을 추가한다.

- 장점: core ABI를 건드리지 않는다.
- 단점: backend마다 설정 경로가 달라지고 local/group caller가 서로 다른 계약을 배운다.
- 단점: `LeaderGroupElectionOptions`를 직접 조합하는 기존 caller가 정책을 전달할 수 없다.

**기각 사유**: single/group 대칭성과 public caller ergonomics를 훼손한다.

### 접근 C - DB schema의 `CURRENT_TIMESTAMP` expression만 사용

비교 predicate는 DB expression으로 바꾸고 Kotlin의 current time 값을 없앤다.

- 장점: JVM clock 영향이 줄어든다.
- 단점: Exposed dialect별 expression과 `Instant` 매핑이 달라지고, lease 계산·min release·extend의
  동일 시각 기준을 한 transaction 안에서 보장하기 어렵다.

**기각 사유**: 이미 검증된 `SELECT CURRENT_TIMESTAMP` conversion을 재사용하는 것보다 dialect
  위험과 변경 범위가 크다.

## 4. 선택한 설계

### 4.1 공개 옵션과 호환성

```kotlin
data class LeaderGroupElectionOptions(
    val maxLeaders: Int = DefaultMaxLeaders,
    val waitTime: Duration = DefaultWaitTime,
    val leaseTime: Duration = DefaultLeaseTime,
    val nodeId: String = LeaderNodeId.Default,
    val minLeaseTime: Duration = Duration.ZERO,
    val useDbTime: Boolean = false,
)
```

필드는 마지막에 추가하여 기존 positional source 호출을 보존한다. 기본값 `false`는 기존 JVM
clock 동작을 유지한다. `useDbTime=true`일 때만 Exposed JDBC/R2DBC ownership transaction이
`SELECT CURRENT_TIMESTAMP`를 사용한다. 옵션 KDoc은 한국어로 갱신하고 single option과 같은
의미를 명시한다. 구현 전 0.5.0 artifact와 기준 클래스의 `javap -p -s` descriptor inventory를
고정한다. Kotlin `Duration` value-class mangling으로 기존 constructor는 private
`(IJJLjava/lang/String;J)V`, 기존 data-class copy는 `copy-5t7Pxr8`와 그 `$default` descriptor로
관찰되므로 “public 5개 인자 constructor”라는 가정은 하지 않는다. 새 data class의 6개 필드
생성 메서드는 유지하되, old copy descriptor는 `@JvmName("copy-5t7Pxr8")` overload와
`@JvmStatic @JvmName("copy-5t7Pxr8\$default")` companion bridge로 보존하고, old private
constructor descriptor는 private secondary constructor로 보존한다. 기존 copy 호출은 현재
인스턴스의 `useDbTime` 값을 그대로 유지하고, 새 6개 인자 copy만 정책을 변경한다.
`component6`와 getter는 additive API로 추가한다. Java caller가 mangled copy를 직접 호출한다는
검증은 요구하지 않고, Kotlin compile/reflection/`javap` 및 `checkBinaryCompatibility`로
실제 descriptor와 visibility를 확인한다. descriptor가 보존되지 않으면 구현을 중단하고
호환성 gap으로 DoD에 남긴다.
`serialVersionUID`는 유지하고 기존 직렬화 payload에 없는 Boolean은 Java serialization의
기본값 `false`로 읽히는지 확인한다. 옵션에는 SQL 문자열, lambda, token을 추가하지 않는다.

### 4.2 내부 current-time primitive

각 adapter에 다음 형태의 `internal` helper를 둔다.

```kotlin
internal fun JdbcTransaction.currentTime(
    useDbTime: Boolean,
    clock: Clock = Clock.systemUTC(),
): Instant
internal suspend fun R2dbcTransaction.currentTime(
    useDbTime: Boolean,
    clock: Clock = Clock.systemUTC(),
): Instant
```

`useDbTime=true`이면 transaction 안에서 고정 SQL `SELECT CURRENT_TIMESTAMP`를 한 번 조회하고,
false이면 `Instant.now(clock)`를 사용한다. 사용자 제공 SQL, dialect expression, 또는 DB 조회
실패 시 JVM fallback은 허용하지 않는다. 각 ownership transaction의 DB-time SQL 예산은 정확히
1회이며, local-time 경로는 0회여야 한다. test-only SQL recorder는 total SQL count와
`CURRENT_TIMESTAMP` count를 분리해 acquire update/insert 양 branch, `isHeld`, `extend`,
`activeCount`, min-release update는 transaction마다 time query 1회, delete release와
local-time 경로는 0회라는 계약을 검증한다. retry wait 자체는 transaction 밖에서 수행하여
connection pool을 잠근 채 대기하지 않는다. 기존 single lock은 기본 인자를 사용하고, group
lock은 production에서 `Clock.systemUTC()`를 전달한다. JDBC/R2DBC의 `Timestamp`, `Instant`,
`OffsetDateTime`, `ZonedDateTime`, `LocalDateTime` 변환은 기존 single lock의 지원 범위를
유지하며 `LocalDateTime`은 UTC로 해석한다. JDBC는 `Timestamp`를 포함하고 R2DBC는 driver가
반환하는 `Instant`/`OffsetDateTime`/`ZonedDateTime`/`LocalDateTime`을 우선 지원하며, 실제
enabled dialect의 반환형을 테스트로 고정한다. production 생성자는 `Clock.systemUTC()`를 기본값으로
사용하며, `Clock`은 내부 group-lock constructor의 마지막 optional seam이라 public API에
노출하지 않는다.

### 4.3 적용 범위

다음 모든 ownership 시간 계산은 동일 transaction current-time primitive를 호출한다.

- `ExposedJdbcGroupLock.tryAcquireOnce`
- `ExposedJdbcGroupLock.isHeldByCurrentInstance`
- `ExposedJdbcGroupLock.unlock`의 min-lease update
- `ExposedJdbcGroupLock.extendDetailed`
- `ExposedJdbcLeaderGroupElector.activeCount`
- R2DBC의 대응 group lock 네 경로
- `ExposedR2DbcSuspendLeaderGroupElector.activeCountSuspend`

JDBC sync/async group path는 같은 `ExposedJdbcGroupLock` constructor에
`options.leaderGroupOptions.useDbTime`를 전달한다. R2DBC suspend path도 같은 값을 전달한다.
`activeCount` predicate는 해당 elector option으로 helper를 호출한다. elector는 production에서
`Clock.systemUTC()`를 helper에 전달하고, DB-time 모드에서는 이 local clock이 사용되지 않는다.
active-count deterministic 검증은 고정 `Clock`을 elector에 주입하는 방식이 아니라 DB가 반환한
`CURRENT_TIMESTAMP`와 row의 `lockedUntil`을 비교하고 statement capture로 정책을 확인한다.
R2DBC의 동기 `activeCount()`가 local cache만 읽는 기존 계약은 이번 issue의 DB-time predicate
범위에서 제외하고, `activeCountSuspend()`와 JDBC `activeCount()`를 authoritative DB 조회로
검증한다. cache-only API의 의미는 manual에 명시한다.

`tryLock`의 wait deadline은 blocking JDBC의 기존 `MonotonicDeadline`을 유지하고, R2DBC wall-clock
deadline은 #669에서 별도로 수정한다. history event timestamp와 history row의 관측 필드는
DB ownership clock 정책에 포함하지 않는다.

min-lease release update는 DB current time을 transaction 안에서 한 번 읽고 `token AND
lockedUntil > now` predicate를 함께 사용한다. 만료된 동일-token row를 stale holder가 되살릴 수
없어야 하며, delete release에는 time query를 추가하지 않는다.

### 4.4 실패와 cancellation

기존 계약을 유지한다. 정상 contention은 `false`/`null`로 반환하고, DB 오류 로깅과 cancellation
재전파는 기존 경로를 보존한다. `useDbTime=false`는 기존 local-time fallback/active-count
계약을 유지하지만 `useDbTime=true`의 timestamp 조회 실패는 JVM time으로 조용히 fallback하지
않는다. 특히 JDBC `activeCount`와 R2DBC `activeCountSuspend`는 실패 시 `maxLeaders`를
반환하여 `availableSlots == 0`인 보수적 unavailable 상태를 만든다. R2DBC elector는
lockName별 `ConcurrentHashMap` unavailable set을 원자적으로 유지하고, 성공한 DB refresh에서
제거한다. cache-only `activeCount()`, `availableSlots`, `state`는 unavailable 동안 같은
보수 값을 노출한다. `runIfLeader`는 stale cache로 조기 차단하지 않고 실제 `tryLock`을
시도하며, DB-time 오류가 try-lock에서 `null`/skip으로 전파되어 새 ownership을 fail-closed한다.
성공한 다음 transaction은 unavailable 상태를 해제하고 DB count를 다시 반영한다. DB-time 실패를
정상적인 ownership으로 간주하지 않는다. R2DBC suspend 경로는 `CancellationException`을 broad
catch에서 삼키지 않으며, lock 획득 직후 cleanup guard를 열어 `recordAcquired`/watchdog 초기화
중 취소에도 `NonCancellable` unlock과 cache 복구가 실행되도록 한다. acquire/action 중 취소 시
원래 취소 예외를 재전파하고, 복구 후 다음 transaction은 다시 DB-time 조회를 수행해야 한다.

## 5. 검증 설계

### 5.1 core/API

- `LeaderGroupElectionOptionsTest`에서 기본 `useDbTime == false`와 custom `true`를 검증한다.
- `copy`, equality, default, validation 기존 테스트가 새 필드와 함께 유지되는지 확인한다.
- ABI spike에서 후보 bridge를 먼저 compiler/`javap`/Java caller로 확인하고, 기준 커밋의 frozen
  serialized bytes 또는 이전 artifact를 fixture로 사용해 `ObjectStreamClass` UID와 기존
  constructor/copy/`copy$default` descriptor를 검증한다.
- 기존 직렬화 payload를 새 옵션으로 읽을 때 `useDbTime=false`가 되는지와 새 `true` 값이
  왕복되는지 확인한다.

### 5.2 JDBC

`ExposedJdbcGroupLockTest`와 `ExposedJdbcLeaderGroupElectionTest`를 `TestDB` enabled dialect에
대해 확장한다.

- `useDbTime=true`인 acquire/isHeld/min-lease release/extend가 DB `CURRENT_TIMESTAMP` 기준을
  사용함을 확인한다.
- 내부 `Clock.fixed`를 서로 큰 양/음의 offset으로 주입한 contender를 사용해, DB time 선택 시
  유효한 row를 조기 takeover하지 않고 token ownership을 되살리지 않음을 검증한다.
- group elector option을 통해 `activeCount`와 `maxLeaders` 경로가 같은 정책을 받는지 확인한다.
- 기존 blocking 및 async group 경로가 기본값에서 동일하게 동작하는지 확인한다.
- test-only SQL recorder로 total SQL과 time-query 수를 구분하여 acquire update/insert 양
  branch, `isHeld`, `extend`, `activeCount`, min-release update는 정확히 1회, delete release와
  local-time은 0회임을 확인한다. timestamp 조회를 한 번 실패한 뒤 다음 transaction에서 다시
  1회 조회하는 recovery도 포함한다. bounded connection pool보다 많은 contender가 기다려도
  wait가 transaction 밖에서 이루어져 pool 고갈/connection leak가 없는지 H2 fast suite로
  고정한다.

### 5.3 R2DBC

`ExposedR2dbcGroupLockTest`와 `ExposedR2DbcSuspendLeaderGroupElectorTest`를 `TestR2dbcDB`
enabled dialect에 대해 확장한다.

- JDBC와 같은 acquire/isHeld/min-lease release/extend 및 expired-token 회귀를 suspend transaction으로 검증한다.
- `activeCountSuspend`가 DB time predicate를 사용하고 local cache contract를 깨지 않는지 확인한다.
- `runIfLeader`의 maxLeaders 동시 경합과 cancellation cleanup을 기존 `runSuspendIO`/coroutine
  tester 규칙으로 검증한다.
- acquire/action 중 cancellation이 `CancellationException`을 재전파하고 row/token 정리,
  history 상태, active-count/cache 복구를 보장하는지 검증한다.
- 명시적 bounded R2DBC pool보다 많은 contender, 짧은 deadline, connection 반환/leak assertion과
  실제 driver timestamp 반환형 conversion을 별도 테스트한다. setup 중 취소, 다음 재획득,
  실패 후 recovery를 포함한다.

실제 TestDB/TestR2dbcDB에서 H2, PostgreSQL, MySQL provider가 비활성인 경우는 명령 출력과
정확한 backend gap을 DoD에 기록하며, 구현을 성공으로 과장하지 않는다.

`LEADER_TEST_DB`의 허용 값은 `H2`, `POSTGRESQL`/`POSTGRES`, `MYSQL_V8`/`MYSQL`로 고정하고,
그 밖의 값은 JDBC/R2DBC test fixture에서 즉시 오류로 fail-closed한다. 미설정은 전체 matrix가
아니라 “full run”으로 명시된 별도 실행으로 기록한다.

검증 명령은 다음처럼 provider별로 고정하고 순차 실행한다. `LEADER_TEST_DB`가 provider를
하나만 선택하는 경우 전체 matrix PASS로 기록하지 않는다.

```bash
./gradlew :bluetape4k-leader-core:test --tests 'io.bluetape4k.leader.LeaderGroupElectionOptionsTest' --tests 'io.bluetape4k.leader.LeaderGroupElectionOptionsCompatibilityTest'
./gradlew :bluetape4k-leader-exposed-jdbc:test --tests '*CurrentTimeTest' --tests '*GroupLockTest'
./gradlew :bluetape4k-leader-exposed-r2dbc:test --tests '*CurrentTimeTest' --tests '*GroupLockTest'
LEADER_TEST_DB=h2 ./gradlew :bluetape4k-leader-exposed-jdbc:test --tests '*Group*Test'
LEADER_TEST_DB=postgresql ./gradlew :bluetape4k-leader-exposed-jdbc:test --tests '*Group*Test'
LEADER_TEST_DB=mysql ./gradlew :bluetape4k-leader-exposed-jdbc:test --tests '*Group*Test'
LEADER_TEST_DB=h2 ./gradlew :bluetape4k-leader-exposed-r2dbc:test --tests '*Group*Test'
LEADER_TEST_DB=postgresql ./gradlew :bluetape4k-leader-exposed-r2dbc:test --tests '*Group*Test'
LEADER_TEST_DB=mysql ./gradlew :bluetape4k-leader-exposed-r2dbc:test --tests '*Group*Test'
./gradlew checkBinaryCompatibility
./gradlew exportManualModuleInventory
ruby scripts/manual/release_inventory.rb 0.5.0 721a9a3808f67489d2bdb8177734325981c24977 build/manual/module-inventory.json build/manual/release-module-inventory.json 35
ruby scripts/manual/validate_release_manuals.rb 0.5.0 721a9a3808f67489d2bdb8177734325981c24977
git diff --check
```

각 provider가 환경상 비활성인 경우 명령의 실제 출력, 원인, 대체 검증을 기록한다.

### 5.4 문서

`docs/manual/en/backends/exposed-sql.md`와 한국어 counterpart에 다음을 같은 구조로 추가한다.

- `LeaderGroupElectionOptions(useDbTime = true)` 예시
- JDBC blocking/async와 R2DBC suspend에서의 적용 범위
- 기본값 `false`, clock skew가 있는 다중 노드에서는 `true` 권장
- history event timestamp와 R2DBC wait deadline은 별도 의미/범위라는 설명
- DB server time 조회 비용과 운영 전제

문서 예시는 `LeaderGroupElectionOptions(useDbTime = true)` 단독 호출로 끝내지 않고, 실제
구성 경로인 `ExposedJdbcLeaderGroupElectionOptions(leaderGroupOptions = ...)`와
`ExposedR2dbcLeaderGroupElectionOptions(leaderGroupOptions = ...)`의 package/import/factory
사용을 각각 컴파일 가능한 EN/KO 예제로 보여준다. JDBC blocking/async와 R2DBC suspend의
차이, R2DBC 동기 `activeCount()`의 cache-only 의미도 함께 적는다. 동일 authoritative DB를
공유해야 하며 read/write routing·failover, timezone/precision, round-trip/pool 비용을
운영 전제로 명시한다.

현재 manual front matter의 `releaseRef: 0.5.0`/immutable `releaseCommit`은 유지한다. 이
issue의 API는 0.6.0+ develop 문맥에서 추가되므로 manual 문구에 적용 버전을 명시하고,
release pin 갱신은 publish gate에서 별도로 수행한다. 이 작업에서는 `manifest.yaml`이나
release pin을 임의로 바꾸지 않으며, 링크/구조 validator와 pin provenance를 DoD에 기록한다.

README는 manual의 간결한 진입점 계약을 유지하되, 다음 EN/KO 파일의 options snippet에
`useDbTime` 의미와 manual 링크를 추가한다: root `README.md`, `README.ko.md`,
`leader-core/README.md`, `leader-core/README.ko.md`, `leader-exposed-jdbc/README.md`,
`leader-exposed-jdbc/README.ko.md`, `leader-exposed-r2dbc/README.md`,
`leader-exposed-r2dbc/README.ko.md`. root README의 기존 잘못된
`io.bluetape4k.leader.core.LeaderGroupElectionOptions` import는 실제 package
`io.bluetape4k.leader.LeaderGroupElectionOptions`로 함께 수정한다.

문서 수용 검증은 release provenance matrix를 포함한다. pinned manual은 `0.5.0`/고정 SHA를
그대로 유지하고 새 API 예제에는 `0.6.0+` 또는 develop 문맥을 표시한다. JDBC 예제는
`Database.connect(dataSource)`와 `ExposedJdbcLeaderGroupElectionOptions` wrapper를 사용하고,
R2DBC 예제는 `suspend fun`/`coroutineScope` 안에서 `ExposedR2dbcLeaderGroupElectionOptions`
factory를 생성한다. 지원 범위 표는 JDBC blocking/async와 R2DBC suspend만 `useDbTime` 소비자로
표시하고 core/Redis 등 비-Exposed backend에서는 활성화하지 말라는 오용 방지 문구를 포함한다.

## 6. 수용 기준 매핑

| Issue #667 기준 | 구현/검증 산출물 |
|---|---|
| 그룹 DB server time 정책과 EN/KO 문서 | `LeaderGroupElectionOptions.useDbTime`, 양쪽 `exposed-sql.md`, 관련 README locale snippet |
| acquire/activeCount/`isHeld`/min release/extend 공통 primitive | JDBC/R2DBC helper 추출과 모든 ownership 경로 호출 |
| H2/PostgreSQL/MySQL clock-offset multi-contender 회귀 | 고정 offset `Clock` group-lock 테스트와 enabled backend matrix 결과 |
| 기본값·기존 API 호환성 | 마지막 optional 필드/secondary constructor, core option/API 및 binary compatibility 결과 |
| blocking/suspend 의미 | JDBC sync/async 및 R2DBC suspend targeted tests와 manual 설명 |

Issue DoD의 각 체크박스는 위 provider별 명령, `checkBinaryCompatibility`, `git diff --check`,
manual validator(`validate_manuals.rb`, `validate_release_manuals.rb`, `export_manifest.rb --check`)
및 EN/KO snippet의 실제 package/import 확인 결과에 직접 매핑한다.

## 7. 범위 제외와 위험

- #669의 R2DBC wait deadline monotonic 전환은 별도 issue로 둔다.
- #668의 publication POM license metadata는 별도 issue로 둔다.
- group auto-extension API를 새로 만들지 않는다. 현재 group elector contract와 무관하다.
- DB server time은 매 ownership transaction마다 round-trip을 추가할 수 있으므로, 성능/안정성
  리뷰에서 transaction별 최대 1회 query와 connection-pool 영향을 확인한다.
- 외부 database provider가 `CURRENT_TIMESTAMP` 반환형을 새롭게 바꾸면 conversion helper와
  backend-specific test를 함께 갱신해야 한다.

## 8. 설계 DoD

| 항목 | 상태 |
|---|---|
| 현재 source/issue/문서 근거 확인 | 완료 |
| 3개 접근 방법과 기각 사유 기록 | 완료 |
| API·ownership·failure·test 경계 확정 | 완료 |
| EN/KO 문서와 backend matrix 수용 기준 연결 | 완료 |
| 1차 독립 관점 검토의 P1 대응(쿼리 예산·오류·취소·pool) | 반영 |
| 독립 6관점 spec review | 완료 |
| 2차 관점 결과(ABI·activeCount·history·release pin·README scope) 반영 | 완료 |
| 사용자 written-spec review/승인 | 대기 |
