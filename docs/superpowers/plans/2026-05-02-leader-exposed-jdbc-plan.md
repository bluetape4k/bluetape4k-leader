# leader-exposed-jdbc 구현 계획

- 작성일: 2026-05-02
- 스펙: docs/superpowers/specs/2026-05-02-leader-exposed-jdbc-design.md
- 브랜치: feat/issue-21-exposed-jdbc
- 선행 모듈: `leader-exposed-core` (스키마 정의 -- 이미 구현 완료)
- 참조 구현: `leader-mongodb` (MongoLock / MongoLeaderElection 패턴)

---

## 의존성 그래프

```
T0 (build.gradle.kts)
 └── T1 (RetryStrategy) [complexity: low]
      ├── T1a (RetryStrategyTest) [complexity: low]
      └── T7a (옵션 클래스 + ensureSchema + validateExposedLockName)
           ├── T2 (ExposedJdbcLock, internal constructor)
           │    └── T4 (ExposedJdbcLeaderElection)
           │         ├── T6 (ExposedJdbcVirtualThreadLeaderElection)
           │         │    └── T10 (VirtualThread 테스트)
           │         └── T7b (확장 함수)  ← T4, T5 완성 후
           │
           └── T3 (ExposedJdbcGroupLock, internal constructor)
                └── T5 (ExposedJdbcLeaderGroupElection)
                     └── T7b (확장 함수)  ← T4, T5 완성 후

T4 → T8 (단일 리더 테스트)
T5 → T9 (그룹 리더 테스트)
T8, T9, T10 → T11 (README + CLAUDE.md)
T8, T9, T10 → T12 (KDoc 최종 검수)  ← T12는 T11과 병렬 가능
```

---

## 태스크 목록

### T0: build.gradle.kts 의존성 추가

- **complexity: low**
- **의존성**: 없음
- **구현 위치**: `leader-exposed-jdbc/build.gradle.kts`
- **핵심 구현 포인트**:
  - `api(libs.exposed.java.time)` 추가 -- timestamp 컬럼 DSL (leader-exposed-core가 이미 사용)
  - `testImplementation(libs.h2.v2)` 추가 -- H2 in-memory 빠른 테스트
  - `testImplementation(libs.mysql.connector.j)` + `testImplementation(libs.testcontainers.mysql)` 추가 -- MySQL Testcontainers
  - `testImplementation(libs.bluetape4k.exposed.jdbc.tests)` 추가 -- TestDB, withDb, withTables 유틸
  - 기존 의존성 유지 확인: `api(project(":leader-core"))`, `api(project(":leader-exposed-core"))`, `api(libs.exposed.core)`, `api(libs.exposed.jdbc)`, `api(libs.exposed.dao)`
- **완료 조건**:
  - `./gradlew :leader-exposed-jdbc:dependencies` 에서 H2, MySQL, exposed-java-time, bluetape4k-exposed-jdbc-tests 확인
  - `./gradlew :leader-exposed-jdbc:compileKotlin` 성공

---

### T1: RetryStrategy sealed class 구현

- **complexity: low** ← [H1 수정: 스펙 Section 3.4에 완전한 구현 코드 제공됨, 단순 전사 수준]
- **의존성**: T0
- **구현 위치**: `leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/RetryStrategy.kt`
- **핵심 구현 포인트**:
  - `sealed class RetryStrategy` + `abstract fun delayMs(attempt: Int, remaining: Long): Long`
  - `Jitter(baseDelayMs: Long = 50L)` -- `ThreadLocalRandom.current().nextLong(1, baseDelayMs.coerceAtLeast(2)).coerceAtMost(remaining)` (AWS full jitter)
  - `Exponential(baseDelayMs: Long = 50L, maxDelayMs: Long = 5_000L)` -- `(baseDelayMs * (1L shl attempt.coerceAtMost(10))).coerceAtMost(maxDelayMs).coerceAtMost(remaining)`
  - `Fixed(fixedMs: Long = 50L)` -- `fixedMs.coerceAtMost(remaining)`
  - 모든 서브클래스는 `data class`로 구현 (동등성/복사 지원)
  - `remaining` 파라미터로 deadline 초과 방지 보장
- **완료 조건**:
  - 3가지 전략 모두 `delayMs()` 반환값이 `0 < result <= remaining` 범위 보장
  - Jitter: ThreadLocalRandom 사용으로 스레드 안전
  - 컴파일 성공 + sealed class exhaustive when 동작 확인

---

### T1a: RetryStrategy 단위 테스트

- **complexity: low** ← [H2 추가: T1 대응 단위 테스트]
- **의존성**: T1
- **구현 위치**: `leader-exposed-jdbc/src/test/kotlin/io/bluetape4k/leader/exposed/jdbc/RetryStrategyTest.kt`
- **핵심 구현 포인트**:
  - `Jitter.delayMs(attempt, remaining=1)` → 반환값 = 1 (remaining coerce)
  - `Exponential(attempt=11)` → 오버플로 없이 maxDelayMs로 클램프
  - `Jitter(baseDelayMs=1)` → `nextLong(1, coerceAtLeast(2)=2)` → IllegalArgumentException 없음
  - `Fixed.delayMs(attempt, remaining=10)` → fixedMs.coerceAtMost(10) 정상
  - 모든 전략: `0 < delayMs <= remaining` 범위 보장
- **완료 조건**:
  - 경계값 테스트 (remaining=1, attempt=0, attempt=20) 모두 통과
  - 컴파일 + 테스트 성공

---

### T2: ExposedJdbcLock 구현 (UPDATE+INSERT 패턴, token 기반 fencing)

- **complexity: high**
- **의존성**: T0, T1, T7
- **구현 위치**: `leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/lock/ExposedJdbcLock.kt`
- **핵심 구현 포인트**:
  - **생성자 접근 제어: `internal constructor`** ← [H10 수정: `lock/` 서브패키지 분리로 private constructor는 같은 패키지에서만 접근 가능. internal로 선언하여 동일 모듈(leader-exposed-jdbc) 내 ExposedJdbcLeaderElection이 접근 가능하게 함. 외부 모듈에서 직접 생성 방지]
    - `internal class ExposedJdbcLock internal constructor(db: Database, lockName: String, retryStrategy: RetryStrategy)`
    - `private val token = Base58.randomString(8)`
  - **tryLock(waitTime, leaseTime): Boolean** -- deadline 루프:
    1. `transaction(db) {}` 내에서 UPDATE (만료 조건) -> INSERT (PK 충돌 runCatching) -> SELECT (token 검증)
    2. 성공: `return true`
    3. 실패: `Thread.sleep(retryStrategy.delayMs(attempt++, remaining))` -- **트랜잭션 바깥**에서 sleep (HikariCP 풀 고갈 방지)
    4. deadline 초과: `return false`
  - **오류 처리 계약** ← [I2/C1 수정]:
    - PK 충돌 (INSERT 실패): `runCatching { insert {} }.onFailure { return@transaction false }` → retry (catch-all, vendor code 불필요)
    - DB 연결 오류 / 그 외 SQL 예외: `tryLock` 전체를 `runCatching` 래핑 → `false` 반환 + warn 로그 (재시도 없음)
  - **unlock()** -- `transaction(db) { deleteWhere { (lockName eq name) and (token eq currentToken) } }`, deleted=0이면 warn 로그
  - **isHeldByCurrentInstance(): Boolean** -- `SELECT WHERE (lockName eq name) AND (token eq this.token) AND (lockedUntil greater now)` ← [I2 수정: 만료 조건 포함]
  - Exposed 1.2.0 import: `org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq`, `org.jetbrains.exposed.v1.core.and`, `org.jetbrains.exposed.v1.jdbc.insert` 등
- **완료 조건**:
  - 단일 트랜잭션 내 UPDATE+INSERT+SELECT 원자성 보장
  - sleep이 transaction 블록 바깥에서만 호출됨
  - token 1개 원칙 준수 (인스턴스 생성 시 1회 발급)
  - DB 연결 오류 → false + warn 로그 (예외 미전파)
  - `!!` 미사용, `@Synchronized` 미사용
  - 컴파일 성공

---

### T3: ExposedJdbcGroupLock 구현 (복합 PK 슬롯 락)

- **complexity: high**
- **의존성**: T0, T1, T7
- **구현 위치**: `leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/lock/ExposedJdbcGroupLock.kt`
- **핵심 구현 포인트**:
  - **생성자 접근 제어: `internal constructor`** ← [H10 수정: T2와 동일한 이유]
    - `internal class ExposedJdbcGroupLock internal constructor(db, lockName, slot, retryStrategy)`
    - `private val token = Base58.randomString(8)`
  - **tryLock(waitTime, leaseTime): Boolean** -- T2와 동일한 UPDATE+INSERT+SELECT 패턴, `LeaderGroupLockTable` 사용
    - UPDATE WHERE: `(lockName eq name) and (slot eq slotNumber) and (lockedUntil less now)`
    - INSERT: `lockName`, `slot`, `token`, `lockOwner`, `lockedAt`, `lockedUntil`
    - SELECT 검증: `(lockName eq name) and (slot eq slotNumber)` -> token 일치 확인
  - **오류 처리 계약**: T2와 동일 (PK 충돌 → retry, DB 연결 오류 → false + warn)
  - **unlock()** -- `deleteWhere { (lockName eq name) and (slot eq slotNumber) and (token eq currentToken) }`
  - **isHeldByCurrentInstance() 미포함** ← [L4 결정: 스펙 Section 4.2에 미정의, 그룹 선출은 슬롯 순회로 상태 확인] 필요 시 추후 추가
  - `(lockName, slot)` 복합 PK 충돌 처리: T2와 동일 runCatching 패턴
- **완료 조건**:
  - LeaderGroupLockTable의 복합 PK (lockName, slot) 정합성 보장
  - T2(ExposedJdbcLock)과 동일한 안전 속성 (token fencing, 트랜잭션 바깥 sleep)
  - DB 연결 오류 → false + warn 로그
  - 컴파일 성공

---

### T4: ExposedJdbcLeaderElection 구현

- **complexity: high**
- **의존성**: T2 (ExposedJdbcLock), T7 (옵션 클래스)
- **구현 위치**: `leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/ExposedJdbcLeaderElection.kt`
- **핵심 구현 포인트**:
  - **private constructor + companion invoke 패턴** (MongoLeaderElection 1:1 대응):
    ```
    class ExposedJdbcLeaderElection private constructor(db, options) : LeaderElection
    companion object { operator fun invoke(db, options) { ensureSchema(db); return ExposedJdbcLeaderElection(db, options) } }
    ```
  - **runIfLeader(lockName, action): T?** --
    1. `validateExposedLockName(lockName)`
    2. `ExposedJdbcLock(db, lockName, options.retryStrategy).tryLock(waitTime, leaseTime)`
    3. 실패 -> `return null`
    4. 성공 -> `try { action() } finally { runCatching { lock.unlock() } }`
  - **runAsyncIfLeader(lockName, executor, action): CompletableFuture<T?>** --
    `CompletableFuture.supplyAsync({ lock.tryLock(...) }, executor).thenComposeAsync(...)` (MongoDB 패턴 동일)
  - **이력 기록 (recordHistory)**: options.recordHistory == true 일 때 ACQUIRED/COMPLETED/FAILED 이력 INSERT/UPDATE (best-effort, runCatching)
  - CancellationException 재throw 필수 (catch 앞에 항상 분리)
- **완료 조건**:
  - `LeaderElection` + `AsyncLeaderElection` 인터페이스 계약 충족
  - private constructor 강제 (외부 직접 생성 불가)
  - ensureSchema 1회 보장
  - never-throws 계약: tryLock 실패 -> null 반환 (예외 없음)
  - action 예외 -> finally에서 lock.unlock() 보장
  - 이력 기록 실패가 리더 실행을 막지 않음 (best-effort)
  - 컴파일 성공

---

### T5: ExposedJdbcLeaderGroupElection 구현 (슬롯 순회 + group 이력)

- **complexity: medium**
- **의존성**: T3 (ExposedJdbcGroupLock), T7 (옵션 클래스)
- **구현 위치**: `leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/ExposedJdbcLeaderGroupElection.kt`
- **핵심 구현 포인트**:
  - **private constructor + companion invoke 패턴** (T4와 동일)
  - **슬롯 순회 알고리즘**: `start = Random.nextInt(maxLeaders)`, `perSlotWait = waitTime / maxLeaders`
    - `for (i in 0 until maxLeaders)` -> `slot = (start + i) % maxLeaders` -> `ExposedJdbcGroupLock.tryLock(perSlotWait, leaseTime)`
    - 첫 성공 슬롯에서 action 실행 후 return, 모든 실패 -> null
  - **runIfLeader / runAsyncIfLeader**: MongoLeaderGroupElection 패턴 1:1 대응
  - **activeCount(lockName)**: `transaction(db) { LeaderGroupLockTable.selectAll().where { lockName eq ... and lockedUntil greater now }.count().toInt() }`
  - **availableSlots(lockName)**: `maxLeaders - activeCount(lockName)`
  - **state(lockName)**: `LeaderGroupState(lockName, maxLeaders, activeCount(lockName))`
  - 이력 기록: recordHistory 옵션 지원 (slot 번호 포함)
- **완료 조건**:
  - `LeaderGroupElection` + `AsyncLeaderGroupElection` 인터페이스 계약 충족
  - 랜덤 시작 순회로 핫스팟 방지
  - maxLeaders개 동시 실행 제한 정확성
  - 컴파일 성공

---

### T6: ExposedJdbcVirtualThreadLeaderElection 구현 (delegate 패턴)

- **complexity: medium**
- **의존성**: T4 (ExposedJdbcLeaderElection)
- **구현 위치**: `leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/ExposedJdbcVirtualThreadLeaderElection.kt`
- **핵심 구현 포인트**:
  - **직접 생성 허용** (private constructor 없음) -- delegate가 이미 invoke()로 ensureSchema 보장
  - 생성자: `(delegate: ExposedJdbcLeaderElection)` -- ExposedJdbcLeaderElection 인스턴스를 받음
  - `VirtualThreadLeaderElection` 인터페이스 구현:
    ```kotlin
    override fun <T> runAsyncIfLeader(lockName: String, action: () -> T): VirtualFuture<T?> =
        virtualFuture { delegate.runIfLeader(lockName, action) }
    ```
  - `virtualFuture` import: `io.bluetape4k.concurrent.virtualthread.virtualFuture` (leader-core -> bluetape4k-core 전이 의존성)
  - 중첩 트랜잭션 문제 없음: delegate.runIfLeader()가 이미 blocking JDBC이므로 virtualFuture로 래핑만
- **완료 조건**:
  - `VirtualThreadLeaderElection` 인터페이스 계약 충족
  - `VirtualFuture<T?>` 반환 타입 정확
  - delegate 패턴으로 코드 중복 없음
  - 컴파일 성공

---

### T7a: 옵션 클래스 + ensureSchema + validateExposedLockName

- **complexity: medium**
- **의존성**: T1 (RetryStrategy)
- **구현 위치**:
  - `leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/ExposedJdbcLeaderElectionOptions.kt`
  - `leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/ExposedJdbcLeaderGroupElectionOptions.kt`
  - `leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/lock/ExposedJdbcSchemaInitializer.kt`
- **핵심 구현 포인트**:
  - **ExposedJdbcLeaderElectionOptions**: `data class(leaderOptions, retryStrategy, recordHistory, lockOwner: String? = null)` + `Serializable`
    - [C2 수정]: `lockOwner: String? = null` 사용. `defaultLockOwner()` 함수 없음 — 스펙 Section 5.1이 권위 소스
    - init 블록: `lockOwner?.let { require(it.length <= 255) { "lockOwner must be <= 255 chars" } }`
    - `companion object { @JvmField val Default = ExposedJdbcLeaderElectionOptions() }`
  - **ExposedJdbcLeaderGroupElectionOptions**: `data class(leaderGroupOptions, retryStrategy, recordHistory, lockOwner: String? = null)` + `Serializable`
    - `val maxLeaders: Int get() = leaderGroupOptions.maxLeaders`
    - init 블록: `require(maxLeaders > 0)` + lockOwner 길이 검증
  - **ExposedJdbcSchemaInitializer (ensureSchema)**:
    - `ConcurrentHashMap<String, Boolean>` 기반 1회 실행 보장
    - `transaction(db) { SchemaUtils.createMissingTablesAndColumns(*ExposedLeaderSchema.allTables) }`
    - key = `db.url`
    - [H8 수정]: `SchemaUtils.createMissingTablesAndColumns` 실패 시 guard key 제거 — MongoDB `MongoLock.ensureIndexes` 패턴 동일:
      ```kotlin
      initializedDbs.computeIfAbsent(dbKey) {
          runCatching {
              transaction(db) { SchemaUtils.createMissingTablesAndColumns(*allTables) }
              true
          }.getOrElse { e ->
              initializedDbs.remove(dbKey)  // 실패 시 guard 제거 → 재시도 허용
              throw e
          }
      }
      ```
  - **validateExposedLockName** ← [C1 수정]: `leader-core`에 `validateLockName` 미존재 → 이 모듈에 직접 구현
    ```kotlin
    fun validateExposedLockName(lockName: String) {
        require(lockName.isNotBlank()) { "lockName must not be blank" }
        require(lockName.length <= 255) { "lockName must be <= 255 chars" }
    }
    ```
- **완료 조건**:
  - data class copy() / equals() / hashCode() 정상 동작
  - lockOwner 255자 초과 시 IllegalArgumentException
  - ensureSchema가 동일 DB URL에 대해 1회만 실행
  - ensureSchema 실패 시 guard key 제거 (재시도 가능)
  - validateExposedLockName: blank → exception, 256자 → exception
  - 컴파일 성공

---

### T7b: 확장 함수 구현

- **complexity: low**
- **의존성**: T4 (ExposedJdbcLeaderElection), T5 (ExposedJdbcLeaderGroupElection) ← [H3 수정: T4/T5 완성 후에만 작성 가능]
- **구현 위치**: `leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/ExposedJdbcLeaderElectionExtensions.kt`
- **핵심 구현 포인트**:
  - `Database.runIfLeader(lockName, options, action)` → `ExposedJdbcLeaderElection(this, options).runIfLeader(lockName, action)` (invoke 경유 → ensureSchema 보장)
  - `Database.runAsyncIfLeader(lockName, executor, options, action)` → `ExposedJdbcLeaderElection(this, options).runAsyncIfLeader(...)`
  - `Database.runIfLeaderGroup(lockName, options, action)` → `ExposedJdbcLeaderGroupElection(this, options).runIfLeader(lockName, action)`
- **완료 조건**:
  - 확장 함수가 invoke() 팩토리 경유 (ensureSchema 보장)
  - 컴파일 성공

---

### T8: 단일 리더 테스트 (AbstractExposedJdbcLeaderTest + H2/PG/MySQL 파라미터화)

- **complexity: medium**
- **의존성**: T4 (ExposedJdbcLeaderElection)
- **구현 위치**:
  - `leader-exposed-jdbc/src/test/kotlin/io/bluetape4k/leader/exposed/jdbc/AbstractExposedJdbcLeaderTest.kt`
  - `leader-exposed-jdbc/src/test/kotlin/io/bluetape4k/leader/exposed/jdbc/ExposedJdbcLeaderElectionTest.kt`
  - `leader-exposed-jdbc/src/test/kotlin/io/bluetape4k/leader/exposed/jdbc/lock/ExposedJdbcLockTest.kt`
- **핵심 구현 포인트**:
  - **AbstractExposedJdbcLeaderTest**: `AbstractExposedTableTest` 패턴 차용
    - `@TestInstance(PER_CLASS)`, `companion object : KLogging()` + `enableDialects(): List<TestDB>`
    - `withDb(testDB) { ... }` / `withTables(testDB, *tables) { ... }` 패턴 사용
  - **ExposedJdbcLockTest** (3-DB 파라미터화):
    - 새 lockName 락 획득 성공
    - 동일 lockName 중복 획득 시 대기 후 실패
    - 만료된 락 재획득 (UPDATE WHERE expired)
    - unlock 토큰 일치/불일치
    - isHeldByCurrentInstance 보유/만료 후 takeover
  - **ExposedJdbcLeaderElectionTest** (3-DB 파라미터화):
    - runIfLeader 기본 성공 경로
    - 동시 접근 (MultithreadingTester) -- 최소 1개 이상 성공
    - blank lockName -> IllegalArgumentException
    - action 예외 -> 전파 + 락 해제
    - contention -> null 반환
    - leaseTime 만료 후 takeover
    - runAsyncIfLeader 비동기 경로
    - [H5 추가] `runIfLeader - action에서 CancellationException 발생 시 actionFailed=false로 즉시 재전파한다`
    - [H5 추가] `runIfLeader - CancellationException 시 이력이 FAILED 아닌 채로 처리된다 (recordHistory=true)`
    - [H6 추가] `runIfLeader - recordHistory=true일 때 이력 ACQUIRED INSERT 실패해도 action이 정상 실행된다`
    - [H6 추가] `runIfLeader - recordHistory=true일 때 이력 COMPLETED UPDATE 실패해도 결과가 반환된다`
  - JUnit 5 + bluetape4k-assertions + backtick 테스트 이름
  - [TE2 수정] `src/test/resources/junit-platform.properties` (`lifecycle.default=per_class`, `parallel.enabled=false`) 생성 확인
- **완료 조건**:
  - 3-DB (H2, PostgreSQL, MySQL_V8) 모두 통과
  - 멀티스레드 경합 테스트 포함
  - takeover 시나리오 검증
  - CancellationException 재전파 검증
  - history best-effort 실패 → action 계속 검증
  - `./gradlew :leader-exposed-jdbc:test` 성공

---

### T9: 그룹 리더 테스트 (AbstractExposedJdbcLeaderGroupTest + H2/PG/MySQL 파라미터화)

- **complexity: medium**
- **의존성**: T5 (ExposedJdbcLeaderGroupElection)
- **구현 위치**:
  - `leader-exposed-jdbc/src/test/kotlin/io/bluetape4k/leader/exposed/jdbc/AbstractExposedJdbcLeaderGroupTest.kt` ← [H4 추가]
  - `leader-exposed-jdbc/src/test/kotlin/io/bluetape4k/leader/exposed/jdbc/ExposedJdbcLeaderGroupElectionTest.kt`
  - `leader-exposed-jdbc/src/test/kotlin/io/bluetape4k/leader/exposed/jdbc/lock/ExposedJdbcGroupLockTest.kt`
- **핵심 구현 포인트**:
  - [H4 수정] **AbstractExposedJdbcLeaderGroupTest**: `AbstractExposedJdbcLeaderTest` 상속. `maxLeaders=3` 기본 설정, `ExposedJdbcLeaderGroupElectionOptions` 생성.
  - **ExposedJdbcGroupLockTest** (3-DB 파라미터화):
    - 슬롯별 락 획득/해제
    - 동일 슬롯 경합 -> 실패
    - 복합 PK (lockName, slot) 충돌 처리
    - [TE6 추가] `unlock - 토큰 불일치 시 다른 소유자의 슬롯 레코드를 삭제하지 않는다`
    - [TE7 추가] `tryLock - leaseTime 만료된 슬롯을 재획득할 수 있다`
  - **ExposedJdbcLeaderGroupElectionTest** (3-DB 파라미터화):
    - runIfLeader 그룹 슬롯 획득 -> action 실행
    - maxLeaders개까지 동시 실행 허용 검증
    - activeCount / availableSlots 정확성
    - 모든 슬롯 점유 시 null 반환
    - runAsyncIfLeader 비동기 경로
  - JUnit 5 + bluetape4k-assertions + backtick 테스트 이름
- **완료 조건**:
  - 3-DB (H2, PostgreSQL, MySQL_V8) 모두 통과
  - maxLeaders 동시 실행 제한 정확성 검증
  - group token fencing (zombie unlock 방지) 검증
  - lease 만료 후 슬롯 재획득 검증
  - `./gradlew :leader-exposed-jdbc:test` 성공

---

### T10: VirtualThread 테스트

- **complexity: medium**
- **의존성**: T6 (ExposedJdbcVirtualThreadLeaderElection)
- **구현 위치**: `leader-exposed-jdbc/src/test/kotlin/io/bluetape4k/leader/exposed/jdbc/ExposedJdbcVirtualThreadLeaderElectionTest.kt`
- **핵심 구현 포인트**:
  - **AbstractExposedJdbcLeaderTest 상속** (3-DB 파라미터화)
  - `runAsyncIfLeader` -> `VirtualFuture.await()` 결과 검증
  - action 예외 시 `VirtualFuture.await()`에서 예외 전파 검증
  - delegate 패턴: `ExposedJdbcLeaderElection(db, options)` 생성 후 `ExposedJdbcVirtualThreadLeaderElection(election)` 래핑
  - JVM 21 VirtualThread 동작 확인
- **완료 조건**:
  - 3-DB (H2, PostgreSQL, MySQL_V8) 모두 통과
  - VirtualFuture 반환 타입 정확
  - 예외 전파 경로 검증
  - `./gradlew :leader-exposed-jdbc:test` 성공

---

### T11: README.md + README.ko.md + CLAUDE.md 업데이트

- **complexity: low**
- **의존성**: T8, T9, T10 (모든 테스트 통과 후)
- **구현 위치**:
  - `leader-exposed-jdbc/README.md`
  - `leader-exposed-jdbc/README.ko.md`
  - `CLAUDE.md` ← [H7 추가]
- **핵심 구현 포인트**:
  - 모듈 목적 설명: RDBMS 기반 분산 리더 선출 (Exposed JDBC)
  - 지원 DB: H2, PostgreSQL, MySQL 8
  - 빠른 시작 예제 코드: ExposedJdbcLeaderElection 생성 -> runIfLeader 호출
  - 그룹 리더 예제: ExposedJdbcLeaderGroupElection
  - VirtualThread 예제: ExposedJdbcVirtualThreadLeaderElection
  - 옵션 설명: RetryStrategy 종류, recordHistory, lockOwner
  - Gradle 의존성 선언 예시
  - leader-mongodb 기존 README 포맷 참조
  - [H7] `CLAUDE.md` Repository Layout 섹션: `leader-exposed/   # (planned)` → `leader-exposed-jdbc/   # Exposed JDBC backend` 로 업데이트
  - [H7] `CLAUDE.md` Interfaces 테이블에 `ExposedJdbcLeaderElection`, `ExposedJdbcLeaderGroupElection`, `ExposedJdbcVirtualThreadLeaderElection` 추가
- **완료 조건**:
  - 영문 README.md + 한국어 README.ko.md 쌍
  - 코드 예제 컴파일 가능한 형태
  - 주요 옵션/설정 설명 포함
  - CLAUDE.md Repository Layout + Interfaces 테이블 반영

---

### T12: KDoc (공개 API 전체)

- **complexity: low**
- **의존성**: T11 (README 완료 후 최종 검수)
- **구현 위치**: T1-T7에서 생성된 모든 `src/main/kotlin/**/*.kt` 파일
- **핵심 구현 포인트**:
  - 모든 public class/interface/function에 한국어 KDoc
  - `@param`, `@return`, `@throws` 태그 포함
  - 코드 예제 (`@sample` 또는 inline code block)
  - MongoDB 대응 클래스 명시 (e.g., "MongoDB의 `MongoLock`에 대응")
  - sealed class RetryStrategy 서브클래스별 사용 가이드
- **완료 조건**:
  - 모든 public API에 KDoc 존재
  - `./gradlew :leader-exposed-jdbc:build` 성공 (KDoc 경고 없음)
  - 한국어 KDoc 일관성

---

## Phase 구분

### Phase 1: 기반 클래스 (T0, T1, T1a, T7a)

| 태스크 | complexity | 핵심 산출물 |
|--------|-----------|------------|
| T0 | low | build.gradle.kts 의존성 완성 |
| T1 | low | RetryStrategy sealed class |
| T1a | low | RetryStrategyTest (경계값 테스트) |
| T7a | medium | Options 2종 + ensureSchema (guard 제거 포함) + validateExposedLockName (로컬) |

### Phase 2: 락 클래스 (T2, T3 — 병렬 가능)

| 태스크 | complexity | 핵심 산출물 |
|--------|-----------|------------|
| T2 | high | ExposedJdbcLock (internal ctor, UPDATE+INSERT+SELECT, token fencing, DB 오류 처리) |
| T3 | high | ExposedJdbcGroupLock (internal ctor, 복합 PK 슬롯 락) |

### Phase 3: Election 클래스 (T4, T5 — 병렬 가능; T6, T7b는 T4/T5 후)

| 태스크 | complexity | 핵심 산출물 |
|--------|-----------|------------|
| T4 | high | ExposedJdbcLeaderElection (private ctor + invoke + 이력) |
| T5 | medium | ExposedJdbcLeaderGroupElection (슬롯 순회) |
| T6 | medium | ExposedJdbcVirtualThreadLeaderElection (delegate) |
| T7b | low | 확장 함수 (T4/T5 의존) |

### Phase 4: 테스트 (T8, T9, T10 — 병렬 가능)

| 태스크 | complexity | 핵심 산출물 |
|--------|-----------|------------|
| T8 | medium | 단일 리더 + Lock 단위 테스트 + CancellationException + history best-effort (3-DB) |
| T9 | medium | 그룹 리더 + GroupLock (token fencing + lease 재획득) (3-DB) |
| T10 | medium | VirtualThread 테스트 (3-DB) |

### Phase 5: 문서 (T11, T12 — 병렬 가능)

| 태스크 | complexity | 핵심 산출물 |
|--------|-----------|------------|
| T11 | low | README.md + README.ko.md + CLAUDE.md 업데이트 |
| T12 | low | KDoc 최종 검수 |

---

## 위험 요소 및 완화

| 위험 | 영향 태스크 | 완화 전략 |
|------|-----------|----------|
| H2 SQL 호환성 차이 | T2, T3, T8, T9 | Exposed DSL만 사용, raw SQL 금지, 3-DB 파라미터화 테스트로 조기 발견 |
| PK 충돌 예외 벤더 차이 | T2, T3 | runCatching catch-all 재시도, vendor code 분기 불필요 |
| HikariCP 풀 고갈 | T2, T3 | Thread.sleep을 transaction 바깥에서만 호출 (구현 규칙으로 강제) |
| Exposed 1.2.0 deprecated API | T2-T6 | `org.jetbrains.exposed.v1.*` import 사용, ide_diagnostics로 deprecated 검출 |
| 타임존 불일치 | T2, T3, T8 | Instant.now() 바인딩 + 테스트 기반에서 UTC 강제 |
| ensureSchema 실패 시 영구 차단 | T7a | 실패 시 guard key 제거 (MongoDB ensureIndexes 패턴 동일) |
| validateLockName 미존재 (leader-core) | T7a | 이 모듈에 직접 구현 — 추후 leader-core 격상 별도 이슈 |

---

## 완료 기준 (DoD)

- [ ] `RetryStrategy` sealed class (Jitter / Exponential / Fixed) ← [H9 추가]
- [ ] `ExposedJdbcLock` (UPDATE+INSERT+SELECT, internal ctor, token fencing)
- [ ] `ExposedJdbcGroupLock` (복합 PK, internal ctor)
- [ ] `ExposedJdbcLeaderElection` (private ctor + invoke + 이력 기록)
- [ ] `ExposedJdbcLeaderGroupElection` (슬롯 순회 + 이력)
- [ ] `ExposedJdbcVirtualThreadLeaderElection` (delegate 패턴)
- [ ] 3-DB (H2, PostgreSQL, MySQL_V8) 모든 테스트 통과
- [ ] CancellationException 재전파 테스트 포함
- [ ] history best-effort 실패 → action 계속 테스트 포함
- [ ] README.md + README.ko.md 작성
- [ ] CLAUDE.md Repository Layout + Interfaces 업데이트
- [ ] KDoc (모든 public API 한국어)

---

## 예상 파일 목록 (최종)

```
leader-exposed-jdbc/
├── build.gradle.kts                                                    [T0]
└── src/
    ├── main/kotlin/io/bluetape4k/leader/exposed/jdbc/
    │   ├── RetryStrategy.kt                                            [T1]
    │   ├── ExposedJdbcLeaderElectionOptions.kt                         [T7a]
    │   ├── ExposedJdbcLeaderGroupElectionOptions.kt                    [T7a]
    │   ├── ExposedJdbcLeaderElection.kt                                [T4]
    │   ├── ExposedJdbcLeaderGroupElection.kt                           [T5]
    │   ├── ExposedJdbcVirtualThreadLeaderElection.kt                   [T6]
    │   ├── ExposedJdbcLeaderElectionExtensions.kt                      [T7b]
    │   └── lock/
    │       ├── ExposedJdbcLock.kt                                      [T2]
    │       ├── ExposedJdbcGroupLock.kt                                 [T3]
    │       └── ExposedJdbcSchemaInitializer.kt                         [T7a]
    └── test/kotlin/io/bluetape4k/leader/exposed/jdbc/
        ├── AbstractExposedJdbcLeaderTest.kt                            [T8]
        ├── AbstractExposedJdbcLeaderGroupTest.kt                       [T9]
        ├── ExposedJdbcLeaderElectionTest.kt                            [T8]
        ├── ExposedJdbcLeaderGroupElectionTest.kt                       [T9]
        ├── ExposedJdbcVirtualThreadLeaderElectionTest.kt               [T10]
        ├── RetryStrategyTest.kt                                        [T1a]
        └── lock/
            ├── ExposedJdbcLockTest.kt                                  [T8]
            └── ExposedJdbcGroupLockTest.kt                             [T9]
```
