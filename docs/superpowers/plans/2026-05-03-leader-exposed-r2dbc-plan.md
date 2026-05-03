# leader-exposed-r2dbc 구현 계획

- 작성일: 2026-05-03
- 스펙: docs/superpowers/specs/2026-05-03-leader-exposed-r2dbc-design.md
- 브랜치: feat/issue-22-exposed-r2dbc
- 선행 모듈: `leader-exposed-core` (스키마 정의 + 공유 유틸), `leader-exposed-jdbc` (1:1 참조 구현체)
- 참조 구현: `leader-exposed-jdbc` (JDBC 패턴), `leader-mongodb` (suspend 팩토리 패턴)

---

## 의존성 그래프

```
T0 (환경 설정: catalog + build.gradle.kts)
 └── T1 (RetryStrategy leader-exposed-core 이동 + typealias)
      ├── T2 (ExposedR2dbcSchemaInitializer)
      │    └── T3 (ExposedR2dbcLock — DB별 분기 포함)
      │         ├── T6 (ExposedR2dbcSuspendLeaderElection)
      │         │    └── T8 (Extension functions) ← T6, T7 완성 후
      │         └── T10 (Lock 단위 테스트) ← T9 필요
      │
      ├── T4 (ExposedR2dbcGroupLock — 복합 PK 슬롯)
      │    ├── T7 (ExposedR2dbcSuspendLeaderGroupElection)
      │    │    └── T8 (Extension functions) ← T6, T7 완성 후
      │    └── T10 (GroupLock 단위 테스트) ← T9 필요
      │
      └── T5 (Options 클래스 2개)
           ├── T6 (단일 리더 Election)
           └── T7 (그룹 리더 Election)

T9 (AbstractExposedR2dbcLeaderTest — 테스트 인프라) ← T0 의존

T10 (Lock 단위 테스트) ← T2, T3, T4, T5, T9
T11 (단일 리더 Election 통합 테스트) ← T6, T9
T12 (그룹 리더 Election 통합 테스트) ← T7, T9
T11, T12 → T13 (CI/Nightly 설정)
T13 → T14 (KDoc + README)
```

---

## 태스크 목록

### T0: 환경 설정 — Version Catalog + build.gradle.kts

- **complexity: low**
- **추정: 30분**
- **의존성**: 없음
- **구현 위치**:
  - `gradle/libs.versions.toml`
  - `leader-exposed-r2dbc/build.gradle.kts`

#### 핵심 구현 포인트

1. **`gradle/libs.versions.toml` 에 R2DBC 드라이버 catalog 항목 추가**:
   - `r2dbc-h2 = { module = "io.r2dbc:r2dbc-h2", version = "1.0.0.RELEASE" }` (버전 확인 필요)
   - `r2dbc-mysql = { module = "io.asyncer:r2dbc-mysql", version = "1.3.1" }` (io.asyncer 그룹, 버전 확인 필요)
   - 기존 `r2dbc-postgresql` 항목은 이미 등록됨 (`org.postgresql:r2dbc-postgresql:1.0.7.RELEASE`)

2. **`leader-exposed-r2dbc/build.gradle.kts` 전면 재작성**:
   - `api(project(":leader-core"))`, `api(project(":leader-exposed-core"))`
   - `api(libs.exposed.core)`, `api(libs.exposed.r2dbc)`, `api(libs.exposed.java.time)`
   - `api(libs.kotlinx.coroutines.core)`, `api(libs.bluetape4k.coroutines)`
   - `compileOnly(libs.r2dbc.postgresql)`, `compileOnly(libs.r2dbc.h2)`, `compileOnly(libs.r2dbc.mysql)`
   - testImplementation: bluetape4k-junit5, bluetape4k-testcontainers, kotlinx-coroutines-test, 3 R2DBC drivers, testcontainers 3종

3. **`exposed-kotlin-datetime` 제거 확인**: 현재 build.gradle.kts에 `exposed-kotlin-datetime`이 있으나, `leader-exposed-core` 테이블이 `org.jetbrains.exposed.v1.javatime.timestamp`를 사용하므로 `exposed-java-time`으로 교체

#### 완료 조건
- `./gradlew :leader-exposed-r2dbc:dependencies` 에서 exposed-r2dbc, exposed-java-time, kotlinx-coroutines-core, r2dbc-postgresql 확인
- `./gradlew :leader-exposed-r2dbc:compileKotlin` 성공 (소스 없으므로 빈 빌드)
- R2DBC 드라이버 3종 catalog alias 사용 가능 확인

---

### T1: RetryStrategy를 leader-exposed-core로 이동 + typealias

- **complexity: medium**
- **추정: 30분**
- **의존성**: T0
- **구현 위치**:
  - 이동 대상: `leader-exposed-core/src/main/kotlin/io/bluetape4k/leader/exposed/retry/RetryStrategy.kt`
  - typealias: `leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/RetryStrategy.kt`

#### 핵심 구현 포인트

1. **RetryStrategy.kt 이동**:
   - `leader-exposed-jdbc/.../jdbc/RetryStrategy.kt` 내용을 `leader-exposed-core/.../exposed/retry/RetryStrategy.kt`로 이동
   - 패키지 선언 변경: `io.bluetape4k.leader.exposed.jdbc` -> `io.bluetape4k.leader.exposed.retry`
   - `leader-exposed-core`는 추가 의존성 불필요 (`RetryStrategy`는 `java.util.concurrent.ThreadLocalRandom` + `java.io.Serializable`만 사용)

2. **leader-exposed-jdbc에 typealias 추가** (바이너리 호환성):
   - 기존 RetryStrategy.kt 파일 내용을 `@Deprecated(level = HIDDEN)` typealias로 교체
   - `@file:Suppress("DEPRECATION_ERROR")` 추가

3. **leader-exposed-jdbc 내부 import 업데이트**:
   - `ExposedJdbcLock`, `ExposedJdbcGroupLock`, `ExposedJdbcLeaderElectionOptions`, `ExposedJdbcLeaderGroupElectionOptions` 등 모든 파일에서 import를 `io.bluetape4k.leader.exposed.retry.RetryStrategy`로 변경

4. **exposed-java-time vs exposed-kotlin-datetime 검증**:
   - `leader-exposed-core` 테이블이 `org.jetbrains.exposed.v1.javatime.timestamp` 사용 -> `exposed-java-time` 유지 확인
   - `leader-exposed-core/build.gradle.kts`에 이미 `api(libs.exposed.java.time)` 있음 -> R2DBC 모듈에서 transitive로 사용 가능

#### 완료 조건
- `./gradlew :leader-exposed-core:compileKotlin` 성공
- `./gradlew :leader-exposed-jdbc:compileKotlin` 성공 (typealias 통해 기존 참조 유지)
- `./gradlew :leader-exposed-jdbc:test` 기존 테스트 전부 통과 (회귀 없음)
- `leader-exposed-r2dbc`에서 `io.bluetape4k.leader.exposed.retry.RetryStrategy` import 가능 확인

---

### T2: ExposedR2dbcSchemaInitializer 구현

- **complexity: medium**
- **추정: 30분**
- **의존성**: T1
- **구현 위치**: `leader-exposed-r2dbc/src/main/kotlin/io/bluetape4k/leader/exposed/r2dbc/lock/ExposedR2dbcSchemaInitializer.kt`

#### 핵심 구현 포인트

1. **JDBC `ExposedJdbcSchemaInitializer` 1:1 대응 suspend 버전**:
   - `ReentrantLock` -> `kotlinx.coroutines.sync.Mutex`
   - `transaction(db) {}` -> `suspendTransaction(db) {}`
   - `SchemaUtils` import: `org.jetbrains.exposed.v1.r2dbc.SchemaUtils` (JDBC와 다른 패키지)

2. **Double-check locking 패턴**:
   - `ConcurrentHashMap<String, Boolean>` + `Mutex`
   - 반드시 `mutex.withLock { }` 사용 (예외 시 자동 해제 보장)
   - `mutex.lock()` / `mutex.unlock()` 직접 호출 금지 -- 예외 발생 시 영구 블로킹 위험

3. **DB key 추출**: `R2dbcDatabase`의 식별자 프로퍼티 확인 (JDBC의 `db.url`에 대응하는 R2DBC 프로퍼티)

4. **`sanitizeUrl()` 함수**: JDBC SchemaInitializer의 sanitizeUrl 재사용 또는 R2DBC URL 형식에 맞게 조정

5. **`validateExposedR2dbcLockName()` 함수**: JDBC의 `validateExposedLockName()`과 동일하게 `leader-core`의 `validateLockName()` 위임

6. **`resetFor(db)` 테스트 유틸**: 테스트에서 초기화 상태 리셋

#### 완료 조건
- `./gradlew :leader-exposed-r2dbc:compileKotlin` 성공
- `Mutex` + `ConcurrentHashMap` double-check 패턴 코드 리뷰 통과
- `suspendTransaction` 내 R2DBC `SchemaUtils` import 정확성 확인

---

### T3: ExposedR2dbcLock 구현 (suspend tryLock/unlock + DB별 분기)

- **complexity: high**
- **추정: 2시간**
- **의존성**: T1, T2
- **구현 위치**: `leader-exposed-r2dbc/src/main/kotlin/io/bluetape4k/leader/exposed/r2dbc/lock/ExposedR2dbcLock.kt`

#### 핵심 구현 포인트

1. **JDBC `ExposedJdbcLock`의 suspend 변환**:
   - `transaction(db) {}` -> `suspendTransaction(db) {}`
   - `Thread.sleep(ms)` -> `delay(ms)` (suspendTransaction **바깥**에서 호출)
   - `Thread.currentThread().interrupt()` -> N/A (코루틴은 `CancellationException`)
   - `KLogging()` 유지 — internal Lock/GroupLock은 sync 내부 클래스이므로 `KLogging()` 사용
   - (Election 구현 클래스 `ExposedR2dbcSuspendLeaderElection` 등은 `KLoggingChannel()`)
   - JDBC `org.jetbrains.exposed.v1.jdbc.insert/update/selectAll/deleteWhere` -> R2DBC `org.jetbrains.exposed.v1.r2dbc.insert/update/selectAll/deleteWhere`

2. **DB별 INSERT 충돌 분기 (CRITICAL -- Risk 1 대응)**:
   - PostgreSQL: `INSERT ... ON CONFLICT DO NOTHING` (UPSERT) 또는 Exposed `upsert {}`
   - H2 / MySQL: `runCatching { insert {} }` 패턴 유지
   - 분기 기준: `db.dialect` 또는 connection metadata 기반 vendor 감지

3. **PostgreSQL INSERT 전략 (우선순위)**:
   - 1차: Exposed R2DBC `upsert {}` 또는 `insertIgnore {}` 지원 여부 확인 -> 지원 시 사용
   - 2차: raw SQL `INSERT INTO ... ON CONFLICT (lock_name) DO NOTHING`
   - 3차: SAVEPOINT 패턴 (raw SQL `SAVEPOINT` / `ROLLBACK TO SAVEPOINT`)

4. **tryLock 루프 구조**:
   - 루프 시작 `currentCoroutineContext().ensureActive()` 호출로 취소 감지
   - `delay(retryStrategy.delayMs(attempt++, remaining))` -- `suspendTransaction` 바깥
   - `CancellationException` catch 시 즉시 rethrow

5. **unlock() KDoc**: `withContext(NonCancellable)` 의무 호출 문서화

6. **isHeldByCurrentInstance()**: JDBC 버전과 동일한 `selectAll().where { token eq ... and lockedUntil greater now }` suspend 버전

#### 완료 조건
- `./gradlew :leader-exposed-r2dbc:compileKotlin` 성공
- DB별 분기 로직에 대한 코드 리뷰: PostgreSQL UPSERT/SAVEPOINT, H2/MySQL runCatching
- `delay()`가 `suspendTransaction` 바깥에서만 호출됨 확인
- `currentCoroutineContext().ensureActive()` 루프 시작에서 호출 확인
- `CancellationException` catch 시 즉시 rethrow 확인

---

### T4: ExposedR2dbcGroupLock 구현 (복합 PK 슬롯)

- **complexity: high**
- **추정: 1시간**
- **의존성**: T1, T2 (T3과 병렬 가능하나, T3의 DB별 분기 패턴을 재사용하므로 T3 이후 권장)
- **구현 위치**: `leader-exposed-r2dbc/src/main/kotlin/io/bluetape4k/leader/exposed/r2dbc/lock/ExposedR2dbcGroupLock.kt`

#### 핵심 구현 포인트

1. **JDBC `ExposedJdbcGroupLock`의 suspend 변환**:
   - `ExposedR2dbcLock`과 동일한 변환 패턴 적용
   - 복합 PK `(lockName, slot)` 조건이 모든 쿼리에 포함

2. **DB별 INSERT 분기**: T3의 패턴을 복합 PK에 맞게 조정
   - PostgreSQL: `INSERT INTO ... (lock_name, slot, ...) ON CONFLICT (lock_name, slot) DO NOTHING`
   - H2/MySQL: `runCatching { LeaderGroupLockTable.insert { ... } }`

3. **`isHeldByCurrentInstance()` 포함** (JDBC GroupLock에는 누락되었으나 R2DBC에서는 포함):
   - `selectAll().where { lockName eq ... and slot eq ... and token eq ... and lockedUntil greater now }`

4. **슬롯 순회 시 DB 오류 즉시 중단 계약**: `tryLock()`이 DB 오류 시 `false` 반환 -> 상위 Election에서 연속 실패 감지 시 루프 종료 권장 (KDoc 문서화)

#### 완료 조건
- `./gradlew :leader-exposed-r2dbc:compileKotlin` 성공
- 복합 PK `(lockName, slot)` 조건이 UPDATE/INSERT/SELECT/DELETE 모든 쿼리에 포함 확인
- `isHeldByCurrentInstance()` 구현 포함 확인
- T3과 동일한 DB별 분기 패턴 적용 확인

---

### T5: Options 클래스 2개 (단일 리더 + 그룹 리더)

- **complexity: low**
- **추정: 20분**
- **의존성**: T1 (RetryStrategy import 경로)
- **구현 위치**:
  - `leader-exposed-r2dbc/src/main/kotlin/io/bluetape4k/leader/exposed/r2dbc/ExposedR2dbcLeaderElectionOptions.kt`
  - `leader-exposed-r2dbc/src/main/kotlin/io/bluetape4k/leader/exposed/r2dbc/ExposedR2dbcLeaderGroupElectionOptions.kt`

#### 핵심 구현 포인트

1. **JDBC Options 클래스 1:1 대응**:
   - `ExposedR2dbcLeaderElectionOptions`: `leaderOptions`, `retryStrategy`, `recordHistory`, `lockOwner`
   - `ExposedR2dbcLeaderGroupElectionOptions`: `leaderGroupOptions`, `retryStrategy`, `recordHistory`, `lockOwner`, `maxLeaders` (위임)
   - `RetryStrategy` import: `io.bluetape4k.leader.exposed.retry.RetryStrategy` (T1에서 이동된 경로)

2. **`init {}` 블록 검증**:
   - `lockOwner?.let { require(it.length <= LOCK_OWNER_LENGTH) { ... } }`
   - GroupOptions: `require(maxLeaders > 0) { ... }`

3. **`companion object { @JvmField val Default = ... }`**: JDBC 패턴과 동일

4. **`data class` + `Serializable`**: 불변, 직렬화 지원

#### 완료 조건
- `./gradlew :leader-exposed-r2dbc:compileKotlin` 성공
- `lockOwner` 길이 검증 테스트 (T10에서 테스트)
- `ExposedR2dbcLeaderElectionOptions.Default` 접근 가능 확인

---

### T6: ExposedR2dbcSuspendLeaderElection 구현

- **complexity: high**
- **추정: 1.5시간**
- **의존성**: T3, T5
- **구현 위치**: `leader-exposed-r2dbc/src/main/kotlin/io/bluetape4k/leader/exposed/r2dbc/ExposedR2dbcSuspendLeaderElection.kt`

#### 핵심 구현 포인트

1. **`SuspendLeaderElection` 인터페이스 구현**:
   - `private constructor` + `suspend operator fun invoke()` 팩토리
   - 팩토리 내 `ExposedR2dbcSchemaInitializer.ensureSchema(db)` 호출

2. **`runIfLeader()` 패턴** (JDBC 참조 + NonCancellable unlock 의무):
   - `validateExposedR2dbcLockName(lockName)`
   - Lock 생성 -> `tryLock()` -> 실패 시 `null` 반환
   - 성공 시: history 기록 -> action 실행 -> finally 블록
   - finally: `withContext(NonCancellable) { history 기록 + runCatching { lock.unlock() } }`

3. **CancellationException 처리**: catch 후 즉시 rethrow, 이력 FAILED 미기록 (취소이므로)

4. **History 기록** (best-effort, `suspendTransaction` 내 동기적):
   - `recordAcquired()`: INSERT -> `LeaderLockHistoryTable.insert { ... }[id]`
   - `recordCompleted()` / `recordFailed()`: UPDATE -> `LeaderLockHistoryTable.update { ... }`
   - R2DBC에서 `insert { ... }[LeaderLockHistoryTable.id]` autoIncrement 반환 검증 필요 (Risk 3)
   - 실패 시 warn 로그 + `null` fallback

#### 완료 조건
- `./gradlew :leader-exposed-r2dbc:compileKotlin` 성공
- `finally` 블록에서 `withContext(NonCancellable)` 사용 확인
- `CancellationException` 재전파 패턴 확인
- `runCatching { lock.unlock() }` 패턴 확인

---

### T7: ExposedR2dbcSuspendLeaderGroupElection 구현

- **complexity: high**
- **추정: 1.5시간**
- **의존성**: T4, T5
- **구현 위치**: `leader-exposed-r2dbc/src/main/kotlin/io/bluetape4k/leader/exposed/r2dbc/ExposedR2dbcSuspendLeaderGroupElection.kt`

#### 핵심 구현 포인트

1. **`SuspendLeaderGroupElection` 인터페이스 구현**:
   - `suspend operator fun invoke()` 팩토리 (MongoDB 패턴 동일)
   - `maxLeaders` 프로퍼티 위임

2. **non-suspend 상태 조회 (캐시 기반, Spec Appendix A)**:
   - `activeCount` 초기값: `0`
   - `availableSlots` 초기값: `maxLeaders` (= maxLeaders - 0)
   - `AtomicInteger` 또는 `atomicfu` 기반 캐시 변수
   - `runIfLeader` 호출 시 내부에서 suspend DB 카운트 실행 -> 캐시 갱신

3. **슬롯 순회 패턴** (JDBC 참조 + suspend 변환):
   - `Random.nextInt(maxLeaders)` 랜덤 시작 -> 핫스팟 방지
   - `perSlotWait = waitTime / maxLeaders` 슬롯별 대기 시간
   - 슬롯 순회 for 루프 내 lock.tryLock 시도
   - 획득 성공 시: history + action + finally `withContext(NonCancellable)`

4. **`state()` 반환**: `LeaderGroupState(lockName, maxLeaders, cachedActiveCount)` (근사값)

#### 완료 조건
- `./gradlew :leader-exposed-r2dbc:compileKotlin` 성공
- 캐시 초기값: `activeCount=0`, `availableSlots=maxLeaders`
- 랜덤 시작 슬롯 구현 확인
- `finally` 블록 `withContext(NonCancellable)` 패턴 확인

---

### T8: Extension Functions

- **complexity: low**
- **추정: 20분**
- **의존성**: T6, T7
- **구현 위치**: `leader-exposed-r2dbc/src/main/kotlin/io/bluetape4k/leader/exposed/r2dbc/ExposedR2dbcLeaderElectionExtensions.kt`

#### 핵심 구현 포인트

1. **`R2dbcDatabase.suspendRunIfLeader()`**: 단일 리더 편의 확장 함수
2. **`R2dbcDatabase.suspendRunIfLeaderGroup()`**: 그룹 리더 편의 확장 함수
3. JDBC의 `Database.runIfLeader()` / `Database.runIfLeaderGroup()` 패턴과 대칭이되, `suspend` prefix로 구분
4. KDoc에 사용 예시 포함

#### 완료 조건
- `./gradlew :leader-exposed-r2dbc:compileKotlin` 성공
- KDoc에 사용 예시 포함 확인

---

### T9: AbstractExposedR2dbcLeaderTest (테스트 인프라)

- **complexity: medium**
- **추정: 1시간**
- **의존성**: T0
- **구현 위치**: `leader-exposed-r2dbc/src/test/kotlin/io/bluetape4k/leader/exposed/r2dbc/AbstractExposedR2dbcLeaderTest.kt`

#### 핵심 구현 포인트

1. **H2 / PostgreSQL / MySQL 3-DB Testcontainers 설정**:
   - PostgreSQL: `PostgreSQLContainer("postgres:15-alpine")`
   - MySQL: `MySQLContainer("mysql:8.0")`
   - H2: in-memory (`r2dbc:h2:mem:///test;DB_CLOSE_DELAY=-1`)
   - `@TestInstance(TestInstance.Lifecycle.PER_CLASS)`

2. **`LEADER_TEST_DB` env var 필터링** (JDBC 패턴 동일):
   - 허용 값: `H2`, `POSTGRESQL`/`POSTGRES`, `MYSQL_V8`/`MYSQL`
   - 미설정 시 3-DB 전체 실행

3. **R2dbcDatabase 연결 팩토리**: dialect 문자열 기반 `R2dbcDatabase.connect()` 분기

4. **`cleanTables()` suspend**: `suspendTransaction(db) { deleteAll() }` 3 테이블

5. **`randomName()` 유틸**: `"test-${UUID.randomUUID().toString().take(8)}"`

6. **`junit-platform.properties`**: 이미 존재 확인 (`PER_CLASS` + `parallel=false`)

#### 완료 조건
- `./gradlew :leader-exposed-r2dbc:compileTestKotlin` 성공
- H2 in-memory + PostgreSQL Testcontainer + MySQL Testcontainer 연결 성공 확인
- `LEADER_TEST_DB` env var 필터링 동작 확인

---

### T10: Lock 단위 테스트 (Lock + GroupLock + SchemaInitializer + Options)

- **complexity: medium**
- **추정: 1.5시간**
- **의존성**: T2, T3, T4, T5, T9
- **구현 위치**:
  - `leader-exposed-r2dbc/src/test/kotlin/io/bluetape4k/leader/exposed/r2dbc/lock/ExposedR2dbcLockTest.kt`
  - `leader-exposed-r2dbc/src/test/kotlin/io/bluetape4k/leader/exposed/r2dbc/lock/ExposedR2dbcGroupLockTest.kt`
  - `leader-exposed-r2dbc/src/test/kotlin/io/bluetape4k/leader/exposed/r2dbc/lock/ExposedR2dbcSchemaInitializerTest.kt`
  - `leader-exposed-r2dbc/src/test/kotlin/io/bluetape4k/leader/exposed/r2dbc/ExposedR2dbcOptionsValidationTest.kt`

#### 핵심 테스트 시나리오

**ExposedR2dbcLockTest**:
1. `tryLock 성공 후 isHeldByCurrentInstance 확인` -- `runSuspendIO { }` 사용
2. `tryLock 경합 시 하나만 성공` -- 두 Lock 인스턴스 동시 시도
3. `토큰 불일치 unlock 무시` -- 다른 인스턴스의 unlock -> 삭제 0건 확인
4. `재시도 전략 Jitter/Exponential/Fixed 적용` -- delay 호출 검증
5. `DB별 INSERT 충돌 패턴 검증` -- PostgreSQL UPSERT, H2/MySQL runCatching
6. 3-DB 파라미터화 (`@MethodSource("enableDialects")`)

**ExposedR2dbcGroupLockTest**:
1. `슬롯별 독립 tryLock/unlock` -- slot 0, slot 1 각각 성공
2. `동일 슬롯 경합` -- 두 GroupLock 인스턴스, 같은 slot, 하나만 성공
3. `isHeldByCurrentInstance 리스 만료 후 false` -- leaseTime 경과 확인

**ExposedR2dbcSchemaInitializerTest**:
1. `최초 호출 시 테이블 생성` -- 테이블 존재 확인
2. `중복 호출 시 idempotent` -- 2회 호출 에러 없음
3. `resetFor 후 재초기화` -- resetFor -> ensureSchema -> 성공

**ExposedR2dbcOptionsValidationTest**:
1. `lockOwner 길이 초과 시 IllegalArgumentException`
2. `maxLeaders <= 0 시 IllegalArgumentException`
3. `Default 인스턴스 정상 생성`

#### 완료 조건
- `./gradlew :leader-exposed-r2dbc:test --tests "*.lock.*"` 통과
- `./gradlew :leader-exposed-r2dbc:test --tests "*OptionsValidation*"` 통과
- 3-DB 파라미터화 테스트 모두 통과

---

### T11: 단일 리더 Election 통합 테스트

- **complexity: medium**
- **추정: 1.5시간**
- **의존성**: T6, T9
- **구현 위치**: `leader-exposed-r2dbc/src/test/kotlin/io/bluetape4k/leader/exposed/r2dbc/ExposedR2dbcSuspendLeaderElectionTest.kt`

#### 핵심 테스트 시나리오

1. **`기본 리더 획득 및 작업 실행`**: 락 획득 -> action 실행 -> 결과 반환 (non-null)
2. **`경합 시 null 반환`**: 두 코루틴 동시 `runIfLeader()` -> 하나만 non-null
3. **`리스 만료 후 재획득`**: leaseTime 경과 후 새 인스턴스 획득 성공
4. **`action 예외 시 락 해제`**: action throw -> 예외 전파 + 락 정상 해제 확인
5. **`코루틴 취소 시 CancellationException 재전파 + 락 해제`**: `cancel()` -> CancellationException 전파 + unlock 실행 확인
6. **`lockName 검증 실패`**: 잘못된 lockName -> IllegalArgumentException
7. **`이력 기록 (recordHistory=true)`**: ACQUIRED -> COMPLETED/FAILED 레코드 확인
8. **`DB 오류 시 null 반환 (never-throws)`**: 트랜잭션 실패 -> null 반환

#### 테스트 패턴
- 모든 테스트 `runSuspendIO { }` 사용 (bluetape4k-coroutines, 실제 IO + delay 정상 동작)
- `@MethodSource("enableDialects")` 3-DB 파라미터화
- 각 테스트 전 `cleanTables(db)` 호출
- lockName은 `randomName()` 사용

#### 완료 조건
- `./gradlew :leader-exposed-r2dbc:test --tests "*SuspendLeaderElectionTest*"` 3-DB 모두 통과
- CancellationException 재전파 + 락 해제 동시 검증 통과
- recordHistory 이력 레코드 존재 확인 통과

---

### T12: 그룹 리더 Election 통합 테스트

- **complexity: medium**
- **추정: 1.5시간**
- **의존성**: T7, T9
- **구현 위치**: `leader-exposed-r2dbc/src/test/kotlin/io/bluetape4k/leader/exposed/r2dbc/ExposedR2dbcSuspendLeaderGroupElectionTest.kt`

#### 핵심 테스트 시나리오

1. **`최대 N개 동시 리더 허용`**: maxLeaders=3, 3개 코루틴 동시 성공, 4번째 null
2. **`슬롯 만료 후 재획득`**: leaseTime 경과 -> 슬롯 재사용
3. **`랜덤 슬롯 시작`**: 여러 반복 실행 -> 시작 슬롯이 다양한지 통계적 검증 (100% 보장 불가, 단 항상 0이 아님 확인)
4. **`상태 조회 일관성`**: activeCount, availableSlots, state 반환값 검증 (캐시 근사값)
5. **`action 예외 시 슬롯 반납`**: action throw -> 슬롯 정상 반환
6. **`코루틴 취소 시 슬롯 반납`**: cancel() -> CancellationException 전파 + 슬롯 해제

#### 테스트 패턴
- 동시성 테스트: `coroutineScope { List(N) { async { election.runIfLeader(...) } }.awaitAll() }`
- PostgreSQL에서만 병행 경합 테스트 실행 (`@EnabledIf` -- Risk 5 대응)

#### 완료 조건
- `./gradlew :leader-exposed-r2dbc:test --tests "*SuspendLeaderGroupElectionTest*"` 통과
- maxLeaders=3 동시 경합 테스트 통과
- 캐시 상태 조회 일관성 확인

---

### T13: CI/Nightly 설정

- **complexity: low**
- **추정: 30분**
- **의존성**: T11, T12
- **구현 위치**: `.github/workflows/nightly.yml`

#### 핵심 구현 포인트

1. **3개 job 추가** (기존 leader-exposed-jdbc 패턴 복사):
   - `test-exposed-r2dbc-h2`: `LEADER_TEST_DB=H2`
   - `test-exposed-r2dbc-postgresql`: `LEADER_TEST_DB=POSTGRESQL` + Testcontainers
   - `test-exposed-r2dbc-mysql`: `LEADER_TEST_DB=MYSQL_V8` + Testcontainers

2. **job 설정** (leader-exposed-jdbc job 참조):
   - `needs: build`
   - 적절한 `GRADLE_OPTS`, `TESTCONTAINERS_RYUK_DISABLED`, `DOCKER_HOST` 설정
   - Kover XML report 생성 + artifact upload

3. **`coverage-report` 및 `nightly-status` jobs의 `needs` 배열에 3개 job 추가**

#### 완료 조건
- nightly.yml YAML 문법 검증
- 3개 job이 올바른 env var 설정으로 구성 확인
- `nightly-status` needs 배열에 포함 확인

---

### T14: KDoc + README

- **complexity: low**
- **추정: 30분**
- **의존성**: T13
- **구현 위치**:
  - `leader-exposed-r2dbc/README.md` (신규)
  - `leader-exposed-r2dbc/README.ko.md` (신규)
  - 모든 public class KDoc 완성

#### 핵심 구현 포인트

1. **README.md / README.ko.md**:
   - 기본 사용법 (Kotlin suspend)
   - DB별 호환성 매트릭스 (H2 / PostgreSQL / MySQL 8)
   - 의존성 추가 방법 (Gradle)
   - Options 설정 가이드
   - leaseTime 설정 권장 사항 (Risk 1.5 대응)
   - Extension functions 사용 예시

2. **KDoc 완성**:
   - 모든 `public` 클래스 / 함수에 KDoc 작성
   - `@param`, `@return`, `@throws` 태그 포함
   - 코드 예시 (`@sample` 또는 inline code block) 포함

3. **CLAUDE.md 업데이트** (필요 시): Repository Layout에 `leader-exposed-r2dbc` 설명 추가

#### 완료 조건
- README.md / README.ko.md 파일 존재
- 모든 public API에 KDoc 작성 확인
- 코드 예시 포함 확인

---

## 태스크 요약

| Task ID | 제목 | 복잡도 | 추정 시간 | 의존성 |
|---------|------|--------|-----------|--------|
| T0 | 환경 설정 -- Version Catalog + build.gradle.kts | low | 30분 | - |
| T1 | RetryStrategy leader-exposed-core 이동 + typealias | medium | 30분 | T0 |
| T2 | ExposedR2dbcSchemaInitializer 구현 | medium | 30분 | T1 |
| T3 | ExposedR2dbcLock 구현 (DB별 분기 포함) | high | 2시간 | T1, T2 |
| T4 | ExposedR2dbcGroupLock 구현 (복합 PK 슬롯) | high | 1시간 | T1, T2 |
| T5 | Options 클래스 2개 | low | 20분 | T1 |
| T6 | ExposedR2dbcSuspendLeaderElection 구현 | high | 1.5시간 | T3, T5 |
| T7 | ExposedR2dbcSuspendLeaderGroupElection 구현 | high | 1.5시간 | T4, T5 |
| T8 | Extension Functions | low | 20분 | T6, T7 |
| T9 | AbstractExposedR2dbcLeaderTest (테스트 인프라) | medium | 1시간 | T0 |
| T10 | Lock 단위 테스트 | medium | 1.5시간 | T2, T3, T4, T5, T9 |
| T11 | 단일 리더 Election 통합 테스트 | medium | 1.5시간 | T6, T9 |
| T12 | 그룹 리더 Election 통합 테스트 | medium | 1.5시간 | T7, T9 |
| T13 | CI/Nightly 설정 | low | 30분 | T11, T12 |
| T14 | KDoc + README | low | 30분 | T13 |

**Total**: ~14시간

---

## 위험 요소 대응 매핑

| Risk | 대응 Task | 검증 방법 |
|------|-----------|-----------|
| Risk 1: PostgreSQL INSERT PK 충돌 | T3 (DB별 분기) | T10 (3-DB PK 충돌 테스트) |
| Risk 1.5: Zombie Holder | T14 (leaseTime 가이드) | T11 (리스 만료 테스트) |
| Risk 2: java.time.Instant 매핑 | T0 (exposed-java-time) | T9 (3-DB 연결 테스트) |
| Risk 3: autoIncrement 반환 | T6 (history INSERT) | T11 (recordHistory 테스트) |
| Risk 4: 코루틴 취소 연결 정리 | T6, T7 (NonCancellable) | T11, T12 (cancel 테스트) |
| Risk 5: H2 동시성 제한 | T9 (DB별 테스트 분리) | T12 (PostgreSQL에서만 경합 테스트) |

---

## 병렬 실행 가능 그룹

1. **Phase 1**: T0 -> T1 (순차)
2. **Phase 2**: T2, T5, T9 (병렬 가능)
3. **Phase 3**: T3, T4 (병렬 가능, 단 T3의 DB별 분기 패턴을 T4가 재사용하므로 T3 우선 권장)
4. **Phase 4**: T6, T7 (병렬 가능)
5. **Phase 5**: T8, T10, T11, T12 (T8은 T6+T7 완성 후, T10-T12는 T9 완성 후)
6. **Phase 6**: T13 -> T14 (순차)

---

## 크리티컬 패스

```
T0 -> T1 -> T2 -> T3 -> T6 -> T11 -> T13 -> T14
                                       (14h 누적)
```

**병목**: T3 (ExposedR2dbcLock, 2h) -- DB별 분기 로직이 가장 복잡하며 T6, T7, T10의 선행 조건.
