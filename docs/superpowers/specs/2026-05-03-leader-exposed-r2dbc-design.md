# leader-exposed-r2dbc 모듈 설계 Spec

- **Issue**: #22
- **Date**: 2026-05-03
- **Status**: Review-Revised
- **Author**: Claude Code (Opus)
- **Base**: leader-exposed-jdbc (참조 구현체), leader-mongodb (suspend 패턴 참조)

---

## 1. 설계 결정 분석

### 결정 1: RetryStrategy 공유 방법

| Option | 설명 | 장점 | 단점 |
|--------|------|------|------|
| **A** | `leader-exposed-core`로 이동 | JDBC+R2DBC 자연스러운 공유, 단일 소스 | exposed-core가 순수 스키마에서 로직 포함 모듈로 변질 |
| **B** | `leader-exposed-r2dbc`에 독립 복사 | 완전 독립, 이동 리팩토링 불필요 | DRY 위반, 버그 수정 시 양쪽 수정 필요 |
| **C** | `leader-core`로 이동 | 전체 백엔드(Redis, Mongo, Exposed) 공유 가능 | Redis/Mongo는 자체 재시도 메커니즘 보유, 과잉 일반화 |

**선정: Option A** -- `leader-exposed-core`로 이동

**근거**:
- `RetryStrategy`는 DB 재시도 전용 로직으로 JDBC/R2DBC 공유가 자연스러움
- R2DBC 변환 시 `delayMs()` 반환값을 `delay(ms)` 호출에 직접 사용 (API 변경 불필요)
- `leader-exposed-core`의 역할을 "스키마 + DB 공통 유틸"로 확장하는 것은 합리적 범위
- Redis/MongoDB는 네이티브 재시도를 제공하므로 `leader-core`까지 올릴 필요 없음
- `delayMs()` 시그니처에 `Thread.sleep()` / `delay()` 의존성이 없으므로 이동 시 의존성 사이클 위험 없음

**마이그레이션 계획**:
1. `RetryStrategy.kt`를 `leader-exposed-core`의 `io.bluetape4k.leader.exposed.retry` 패키지로 이동
2. `leader-exposed-jdbc`의 `build.gradle.kts`에 `:leader-exposed-core`를 `api` 의존성으로 명시 확인 (이미 선언되어 있으나, RetryStrategy 이동 후에도 transitive 접근이 보장되는지 검증)
3. `leader-exposed-jdbc`에 `@Deprecated(level = HIDDEN)` typealias 추가 (바이너리 호환성)
4. `leader-exposed-r2dbc`에서 `io.bluetape4k.leader.exposed.retry.RetryStrategy`로 바로 참조

---

### 결정 2: suspend tryLock 트랜잭션 격리 전략

| Option | 설명 | 장점 | 단점 |
|--------|------|------|------|
| **A** | `REPEATABLE_READ` | 팬텀 리드 방지, 강한 일관성 | PostgreSQL에서 직렬화 실패 시 예외 빈도 증가, R2DBC에서 격리 수준 지정 제한적 |
| **B** | `READ_COMMITTED` + 낙관적 토큰 검증 | DB 기본값, 락 경합 낮음, R2DBC 호환 최적 | 이론적 ABA 문제 (UUID fencing token으로 실질적 방지) |
| **C** | 격리 무관 + pessimistic UPDATE 선행 | `SELECT FOR UPDATE` 기반 | R2DBC에서 `SELECT FOR UPDATE SKIP LOCKED` 미지원, 락 대기 블로킹 |

**선정: Option B** -- `READ_COMMITTED` + 낙관적 토큰 검증

**근거**:
- JDBC 참조 구현체의 UPDATE->INSERT->SELECT 3단계 패턴이 이미 `READ_COMMITTED`에서 정확히 동작
  - Step 1 UPDATE: `lockedUntil < NOW()` 조건이 CAS 역할 수행
  - Step 2 INSERT: PK 충돌이 자연스러운 경쟁 조건 해소
  - Step 3 SELECT: `token = ?` 조건이 최종 소유권 확인
- UUID fencing token이 ABA 문제를 확률적으로 방지 (충돌 확률: ~10^-37)
- R2DBC Exposed는 `suspendTransaction {}` 내에서 커스텀 격리 수준 지정이 제한적일 수 있음
- H2, PostgreSQL, MySQL 8 모두 `READ_COMMITTED`가 기본 격리 수준
- `SELECT FOR UPDATE`는 R2DBC에서 DB별 동작 차이가 크고, `SKIP LOCKED` 미지원 문제 회피

**⚠️ PostgreSQL R2DBC INSERT PK 충돌 대응 (DB별 분기 필수)**:
- PostgreSQL: INSERT 실패 시 트랜잭션 전체 abort → `runCatching { insert {} }` 패턴 사용 불가
- H2 / MySQL: JDBC와 동일하게 `runCatching` 패턴 안전
- **구현 시**: `tryAcquireOnce()` 내부에서 DB 방언(dialect)을 감지하여 분기
  - H2/MySQL: UPDATE → runCatching { INSERT } → SELECT (JDBC 패턴 유지)
  - PostgreSQL: UPDATE → `INSERT ... ON CONFLICT DO NOTHING` 또는 SAVEPOINT 패턴 → SELECT

---

### 결정 3: ExposedR2dbcLock 추상화 레이어

| Option | 설명 | 장점 | 단점 |
|--------|------|------|------|
| **A** | `ExposedR2dbcLock` 클래스 분리 | JDBC와 대칭 구조, 단일 책임, 테스트 용이 | 파일 수 증가 |
| **B** | Election 내부에 직접 DSL 호출 | 레이어 감소, 코드 간결 | Election 클래스 비대화, Group/Single 간 중복 |

**선정: Option A** -- `ExposedR2dbcLock` + `ExposedR2dbcGroupLock` 분리

**근거**:
- JDBC 참조 구현체와 1:1 대칭 구조 -> 코드 리뷰 및 비교가 용이
- Lock 클래스 단위 테스트 가능 (Election 없이 tryLock/unlock 독립 검증)
- `tryLock()` 내부의 `delay()` 루프 + `tryAcquireOnce()` 트랜잭션 분리가 명확
- Group 락의 복합 PK 로직이 Single과 다르므로 분리가 자연스러움
- 파일 수 증가는 소규모 (2개 추가)이고 각 200~300줄 예상

---

### 결정 4: ExposedR2dbcSchemaInitializer

| Option | 설명 | 장점 | 단점 |
|--------|------|------|------|
| **A** | `suspend fun ensureSchema(db)` 순수 suspend | 코루틴 친화적, blocking 없음 | 호출부에서 코루틴 스코프 필요, 팩토리도 suspend |
| **B** | `runBlocking` 사용 초기화 | 동기 팩토리 유지 가능 | `runBlocking`은 코루틴 컨텍스트 내 호출 시 데드락 위험 |

**선정: Option A** -- `suspend fun ensureSchema(db: R2dbcDatabase)`

**근거**:
- R2DBC Database는 비동기 전용이므로 `runBlocking` 내 `suspendTransaction` 호출 시 이벤트 루프 데드락 위험
- `SuspendLeaderElection` 인터페이스 자체가 코루틴이므로 팩토리도 `suspend operator fun invoke()` 패턴 자연스러움
- MongoDB 모듈(`MongoSuspendLeaderElection`)이 이미 동일한 `suspend operator fun invoke()` 팩토리 패턴 사용 중
- Double-check locking을 `Mutex` + `ConcurrentHashMap`으로 구현 (coroutine-safe)

---

### 결정 5: History 기록 방식

| Option | 설명 | 장점 | 단점 |
|--------|------|------|------|
| **A** | `suspendTransaction {}` 동기적 기록 | 원자적, 순서 보장, 실패 시 즉시 감지 | 메인 경로 지연 (미미하지만 존재) |
| **B** | 별도 `launch {}` 비동기 기록 | 메인 경로 무영향, 낮은 레이턴시 | 누락 가능, CoroutineScope 관리 복잡, finally 블록 내 launch 비정상 동작 가능 |

**선정: Option A** -- `suspendTransaction {}` 내 동기적 기록 (best-effort)

**근거**:
- JDBC 참조 구현체가 이미 동기적 기록 + `runCatching` best-effort 패턴 사용
- 이력 INSERT/UPDATE는 단일 행 DML로 지연 ~1ms (무시 가능)
- `launch {}`는 코루틴 취소 시 이력이 유실될 수 있고, `NonCancellable` 내에서 새 코루틴 시작은 구조적 동시성 위반
- finally 블록에서 `withContext(NonCancellable)` 내 `suspendTransaction`은 안전하게 실행됨
- 기록 실패 시 warn 로그만 남기고 진행하는 JDBC 패턴 그대로 유지

---

## 2. 위험 요소 식별

### Risk 1: Exposed R2DBC `suspendTransaction` 내 예외 롤백 불일치 (CRITICAL)
- **심각도**: CRITICAL
- **설명**: R2DBC의 `suspendTransaction` 내부에서 `runCatching`으로 INSERT PK 충돌을 흡수하는 패턴이 JDBC의 `transaction {}`과 동일하게 동작하지 않음. PostgreSQL은 트랜잭션 내 에러 발생 시 해당 트랜잭션의 모든 후속 명령을 거부함 (`ERROR: current transaction is aborted, commands ignored until end of transaction block`). 즉, Step 2 INSERT 실패 후 Step 3 SELECT가 실행 불가.
- **⚠️ SELECT-first 전략의 TOCTOU 레이스**: INSERT 전에 SELECT로 존재 확인 → INSERT 사이에 다른 노드가 INSERT 가능 (Time-of-Check to Time-of-Use). SELECT-first는 round-trip 추가 + 레이스 조건 미해결이므로 PostgreSQL에서 1차 전략으로 채택 불가.
- **대응 (DB별 분기 필수)**:
  - **H2 / MySQL**: JDBC와 동일한 `runCatching { insert {} }` 패턴 유지 (H2는 auto-rollback, MySQL은 statement 실패가 트랜잭션을 abort하지 않음)
  - **PostgreSQL**: 1차 전략 — `INSERT ... ON CONFLICT DO NOTHING` (UPSERT) 또는 `SAVEPOINT` + `ROLLBACK TO SAVEPOINT` 패턴 사용. Exposed R2DBC에서 `upsert {}` / `insertIgnore {}` 지원 여부 확인 후 결정
  - 통합 테스트에서 3-DB 모두 PK 충돌 시나리오 검증 필수
  - **구현 시 `DatabaseDialect` 감지 → 분기 로직 작성** (Risk 1 대응 전략과 Appendix B 결론의 단일화)

### Risk 1.5: leaseTime 만료 후 Zombie Holder 시나리오
- **심각도**: MEDIUM
- **설명**: 노드 A가 leaseTime 내에 action을 완료하지 못하고 lease가 만료된 상태에서 노드 B가 같은 lock을 재획득하면, A와 B가 동시에 동일 lockName에 대한 action을 수행하는 상황 발생 (split-brain). A의 `unlock()`은 이미 B의 token으로 덮어써져 있으므로 아무 효과 없음 (token 불일치 → 삭제 0건 → warn 로그).
- **대응**:
  - leaseTime은 action 최대 실행 시간보다 충분히 크게 설정 (권장: action p99 * 3)
  - `isHeldByCurrentInstance()`로 장시간 action 중간에 lease 유효 여부 확인 가능
  - 사용자 문서에 "leaseTime 설정 가이드" 섹션 추가 (README)
  - token 불일치 unlock은 **의도된 설계**로 warn 로그만 남기며, 이는 zombie 방지 fencing token의 정상 동작

### Risk 2: Exposed R2DBC `java.time.Instant` 매핑
- **심각도**: MEDIUM
- **설명**: `leader-exposed-core`의 테이블이 `org.jetbrains.exposed.v1.javatime.timestamp`를 사용. R2DBC 모듈은 `exposed-kotlin-datetime`에 의존하도록 `build.gradle.kts`가 설정되어 있어, `java.time.Instant` <-> `kotlinx.datetime.Instant` 매핑 불일치 가능성.
- **대응**:
  - `exposed-java-time` 의존성을 `leader-exposed-r2dbc/build.gradle.kts`에 추가
  - 또는 R2DBC 전용 테이블을 `kotlinx.datetime` 기반으로 래핑하는 어댑터 작성
  - `leader-exposed-core`의 테이블 정의가 JDBC/R2DBC 공용이므로 `exposed-java-time` 유지가 현실적

### Risk 3: R2DBC `autoIncrement` / `insertAndGetId` 미지원
- **심각도**: MEDIUM
- **설명**: `LeaderLockHistoryTable.id`가 `long("id").autoIncrement()`로 정의됨. Exposed R2DBC에서 `insert { ... }[LeaderLockHistoryTable.id]` 패턴으로 auto-generated key를 반환하는 것이 JDBC와 동일하게 지원되는지 검증 필요.
- **대응**:
  - Exposed R2DBC API에서 `insertAndGetId` 또는 `insert { }[col]` 반환값 확인
  - 미지원 시 UUID 기반 PK로 변경하거나, RETURNING 절 사용
  - History 기록은 best-effort이므로 `historyId = null` fallback 가능

### Risk 4: 코루틴 취소와 R2DBC 연결 정리
- **심각도**: HIGH
- **설명**: `suspendTransaction` 실행 중 코루틴이 취소되면 R2DBC 연결이 올바르게 반환되는지 확인 필요. 연결 누수(leak) 시 풀 고갈로 전체 시스템 영향.
- **대응**:
  - `withContext(NonCancellable)` 내에서 unlock용 `suspendTransaction` 실행
  - Exposed R2DBC의 취소 안전성 문서 확인
  - 통합 테스트에서 `cancel()` + 연결 풀 모니터링 검증

### Risk 5: H2 R2DBC 드라이버의 동시성 제한
- **심각도**: LOW
- **설명**: H2 R2DBC는 in-memory 모드에서 동시 트랜잭션 지원이 제한적. 병행 락 테스트에서 예상 외 직렬화.
- **대응**:
  - H2 R2DBC 테스트는 기본 동작 검증 용도로 한정
  - 병행 경합 테스트는 PostgreSQL R2DBC에서만 실행 (`@EnabledIf` 조건부)

---

## 3. 모듈 구조 설계

### 패키지 구조

```
leader-exposed-r2dbc/
  build.gradle.kts
  src/
    main/kotlin/io/bluetape4k/leader/exposed/r2dbc/
      lock/
        ExposedR2dbcLock.kt                  # 단일 리더 suspend 락
        ExposedR2dbcGroupLock.kt             # 그룹 리더 suspend 락 (슬롯 기반)
        ExposedR2dbcSchemaInitializer.kt     # R2DBC suspend 스키마 초기화
        ValidateExposedR2dbcLockName.kt      # lockName 검증 (leader-core 위임)
      ExposedR2dbcSuspendLeaderElection.kt     # SuspendLeaderElection 구현체
      ExposedR2dbcSuspendLeaderGroupElection.kt # SuspendLeaderGroupElection 구현체
      ExposedR2dbcLeaderElectionOptions.kt     # 단일 리더 옵션
      ExposedR2dbcLeaderGroupElectionOptions.kt # 그룹 리더 옵션
      ExposedR2dbcLeaderElectionExtensions.kt  # R2dbcDatabase 확장 함수
    test/kotlin/io/bluetape4k/leader/exposed/r2dbc/
      AbstractExposedR2dbcLeaderTest.kt        # 테스트 베이스 (Testcontainers)
      ExposedR2dbcSuspendLeaderElectionTest.kt # 단일 리더 테스트
      ExposedR2dbcSuspendLeaderGroupElectionTest.kt # 그룹 리더 테스트
      ExposedR2dbcOptionsValidationTest.kt     # 옵션 검증 테스트
      lock/
        ExposedR2dbcLockTest.kt              # Lock 단위 테스트
        ExposedR2dbcGroupLockTest.kt         # GroupLock 단위 테스트
        ExposedR2dbcSchemaInitializerTest.kt # 스키마 초기화 테스트
```

### build.gradle.kts (예상)

```kotlin
configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":leader-core"))
    api(project(":leader-exposed-core"))

    // Exposed R2DBC
    api(libs.exposed.core)
    api(libs.exposed.r2dbc)
    // NOTE: exposed-java-time vs exposed-kotlin-datetime 결정 필요
    // leader-exposed-core 테이블이 java.time.Instant (exposed-java-time) 사용 중
    // → Task 1에서 R2DBC 환경 호환성 검증 후 확정 (exposed-java-time 우선 채택 예상)
    api(libs.exposed.java.time)

    // Coroutines
    api(libs.kotlinx.coroutines.core)

    // bluetape4k utilities
    api(libs.bluetape4k.coroutines)

    // R2DBC drivers (compile-only, 사용자가 선택)
    compileOnly(libs.r2dbc.postgresql)
    compileOnly(libs.r2dbc.h2)           // ⚠️ catalog 미등록 → Task 13에서 추가 필요
    compileOnly(libs.r2dbc.mysql)        // ⚠️ io.asyncer:r2dbc-mysql, catalog 미등록 → Task 13에서 추가 필요

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    // ⚠️ bluetape4k-exposed-r2dbc-tests는 catalog 미등록 → JDBC 패턴처럼 독립 구현 채택
    // testImplementation(libs.bluetape4k.exposed.r2dbc.tests)  // 사용하지 않음
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.r2dbc.postgresql)
    testImplementation(libs.r2dbc.h2)
    testImplementation(libs.r2dbc.mysql)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mysql)
}
```

> **⚠️ Catalog 등록 필요 항목** (Task 13에서 처리):
> - `libs.r2dbc.h2` → `io.r2dbc:r2dbc-h2` 추가 필요
> - `libs.r2dbc.mysql` → `io.asyncer:r2dbc-mysql` 추가 필요
> - `libs.bluetape4k.exposed.r2dbc.tests`는 존재하지 않음 → 독립 테스트 인프라로 대체

---

## 4. 핵심 클래스 시그니처

### 4.1 ExposedR2dbcLock (internal)

```kotlin
package io.bluetape4k.leader.exposed.r2dbc.lock

import io.bluetape4k.leader.exposed.RetryStrategy
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import java.time.Duration

/**
 * Exposed R2DBC 기반 suspend 토큰 분산 락.
 *
 * JDBC의 ExposedJdbcLock과 동일한 UPDATE+INSERT+SELECT 패턴을 
 * suspendTransaction 기반으로 구현합니다.
 *
 * - tryLock 내부에서 delay() 사용 (Thread.sleep 대신)
 * - tryAcquireOnce는 단일 suspendTransaction 내 실행
 * - 절대 예외를 throw하지 않음; DB 오류 -> false + warn 로그
 */
internal class ExposedR2dbcLock internal constructor(
    private val db: R2dbcDatabase,
    val lockName: String,
    private val retryStrategy: RetryStrategy,
    private val lockOwner: String? = null,
) {
    companion object : KLoggingChannel()  // suspend 컨텍스트 지원 — Lock/GroupLock 모두 KLoggingChannel 통일

    /** 인스턴스별 고유 fencing token (UUID). */
    val token: String

    /**
     * [waitTime] 내에 락 획득을 시도합니다.
     *
     * **⚠️ 구현 제약사항**:
     * - `delay()`는 반드시 `suspendTransaction {}` **바깥**에서 호출해야 함
     *   (R2DBC 연결 점유 방지, JDBC의 "Thread.sleep은 transaction 바깥" 규칙과 대응)
     * - 루프 매 iteration 시작 시 `currentCoroutineContext().ensureActive()` 호출로 취소 감지
     *
     * @param waitTime 최대 대기 시간
     * @param leaseTime 락 보유 TTL
     * @return 획득 성공 시 true
     */
    suspend fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean

    /**
     * 현재 인스턴스(token)가 유효한 락을 보유하고 있는지 확인합니다.
     */
    suspend fun isHeldByCurrentInstance(): Boolean

    /**
     * 현재 인스턴스가 보유한 락을 해제합니다.
     *
     * **⚠️ 반드시 `withContext(NonCancellable)` 블록 안에서 호출해야 합니다.**
     * 코루틴 취소 시 `suspendTransaction`이 중단되면 R2DBC 연결 누수 및
     * zombie lock이 발생할 수 있습니다.
     *
     * ```kotlin
     * finally {
     *     withContext(NonCancellable) {
     *         runCatching { lock.unlock() }
     *     }
     * }
     * ```
     */
    suspend fun unlock()
}
```

### 4.2 ExposedR2dbcGroupLock (internal)

```kotlin
package io.bluetape4k.leader.exposed.r2dbc.lock

import io.bluetape4k.leader.exposed.RetryStrategy
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import java.time.Duration

/**
 * Exposed R2DBC 기반 그룹 suspend 락 (복합 PK 슬롯).
 */
internal class ExposedR2dbcGroupLock internal constructor(
    private val db: R2dbcDatabase,
    val lockName: String,
    val slot: Int,
    private val retryStrategy: RetryStrategy,
    private val lockOwner: String? = null,
) {
    companion object : KLoggingChannel()

    val token: String

    /**
     * [waitTime] 내에 슬롯 락 획득을 시도합니다.
     *
     * - `delay()`는 반드시 `suspendTransaction {}` **바깥**에서 호출 (R2DBC 연결 점유 방지)
     * - DB 오류 시 즉시 종료하지 않고 `false` 반환 후 다음 슬롯으로 넘김
     *   (단, **연속 DB 오류**(예: connection pool 고갈)는 상위 Election에서 즉시 종료 권장)
     * - 루프 내에서 `currentCoroutineContext().ensureActive()` 호출로 취소 감지
     */
    suspend fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean

    /**
     * 현재 인스턴스(token)가 유효한 슬롯 락을 보유하고 있는지 확인합니다.
     *
     * 리스 만료 후 타 인스턴스가 재획득한 경우 `false`를 반환합니다.
     */
    suspend fun isHeldByCurrentInstance(): Boolean

    /**
     * 현재 인스턴스가 보유한 슬롯 락을 해제합니다.
     *
     * **⚠️ 반드시 `withContext(NonCancellable)` 블록 안에서 호출해야 합니다.**
     */
    suspend fun unlock()
}
```

### 4.3 ExposedR2dbcSchemaInitializer (internal)

```kotlin
package io.bluetape4k.leader.exposed.r2dbc.lock

import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

/**
 * R2DBC suspend 스키마 초기화 유틸리티.
 *
 * DB URL 기준 최초 1회만 SchemaUtils.createMissingTablesAndColumns 실행.
 * coroutine-safe: `Mutex.withLock { }` + ConcurrentHashMap double-check.
 *
 * **⚠️ Mutex 사용 시 반드시 `mutex.withLock { }` 패턴 사용** (예외 시 자동 해제 보장).
 * `mutex.lock()` / `mutex.unlock()` 직접 호출 금지 — 예외 발생 시 영구 블로킹 위험.
 */
internal object ExposedR2dbcSchemaInitializer {

    /**
     * 리더 선출 테이블이 없으면 생성합니다 (최초 1회).
     */
    suspend fun ensureSchema(db: R2dbcDatabase)

    /** 테스트용 초기화 상태 리셋. */
    internal fun resetFor(db: R2dbcDatabase)
}
```

### 4.4 ExposedR2dbcSuspendLeaderElection

```kotlin
package io.bluetape4k.leader.exposed.r2dbc

import io.bluetape4k.leader.coroutines.SuspendLeaderElection
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

/**
 * Exposed R2DBC 기반 코루틴 단일 리더 선출 구현체.
 *
 * - SuspendLeaderElection 인터페이스 구현
 * - suspend operator fun invoke() 팩토리 패턴
 * - NonCancellable unlock 보장: `finally { withContext(NonCancellable) { runCatching { lock.unlock() } } }`
 * - best-effort 이력 기록
 *
 * **unlock 패턴 (필수)**:
 * ```kotlin
 * try {
 *     val result = action()
 *     return result
 * } catch (e: CancellationException) { throw e }
 *   catch (e: Throwable) { throw e }
 * finally {
 *     withContext(NonCancellable) {
 *         // history 기록 (best-effort)
 *         runCatching { lock.unlock() }
 *     }
 * }
 * ```
 */
class ExposedR2dbcSuspendLeaderElection private constructor(
    private val db: R2dbcDatabase,
    val options: ExposedR2dbcLeaderElectionOptions,
) : SuspendLeaderElection {

    companion object : KLoggingChannel() {
        /**
         * 인스턴스를 생성합니다. 첫 호출 시 스키마 자동 생성.
         */
        suspend operator fun invoke(
            db: R2dbcDatabase,
            options: ExposedR2dbcLeaderElectionOptions = ExposedR2dbcLeaderElectionOptions.Default,
        ): ExposedR2dbcSuspendLeaderElection
    }

    override suspend fun <T> runIfLeader(
        lockName: String,
        action: suspend () -> T,
    ): T?
}
```

> **Note: Spring Boot DI 주의사항**
> `suspend operator fun invoke()` 팩토리는 Spring `@Bean` 메서드에서 직접 호출할 수 없습니다 (suspend 함수이므로).
> Spring Boot 연동 시 `@Bean` 팩토리에서 `runBlocking { ExposedR2dbcSuspendLeaderElection(db, options) }` 패턴을 사용하거나,
> `leader-spring-boot3` 모듈에서 코루틴 기반 자동 구성을 제공해야 합니다.
> `runBlocking`은 애플리케이션 시작 시 1회만 호출되므로 데드락 위험이 낮지만, 코루틴 컨텍스트 내에서는 사용 금지.

### 4.5 ExposedR2dbcSuspendLeaderGroupElection

```kotlin
package io.bluetape4k.leader.exposed.r2dbc

import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElection
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

/**
 * Exposed R2DBC 기반 코루틴 복수 리더 그룹 선출 구현체.
 *
 * - (lockName, slot) 복합 PK 슬롯 순회
 * - 랜덤 시작 슬롯으로 핫스팟 방지
 * - suspend 전용 (activeCount/availableSlots/state도 suspend)
 *
 * NOTE: SuspendLeaderGroupElection의 상태 조회 메서드가 non-suspend이므로,
 *       R2DBC에서는 최근 갱신된 캐시값 반환 (근사값, 인터페이스 계약 일치).
 *
 * **캐시 초기값 정책**:
 * - `activeCount` 초기값: `0` (runIfLeader 호출 전까지 DB 조회 없음)
 * - `availableSlots` 초기값: `maxLeaders` (= maxLeaders - 0)
 * - 첫 `runIfLeader` 호출 시 내부적으로 suspend 카운트를 갱신하여 캐시 업데이트
 */
class ExposedR2dbcSuspendLeaderGroupElection private constructor(
    private val db: R2dbcDatabase,
    val options: ExposedR2dbcLeaderGroupElectionOptions,
) : SuspendLeaderGroupElection {

    companion object : KLoggingChannel() {
        suspend operator fun invoke(
            db: R2dbcDatabase,
            options: ExposedR2dbcLeaderGroupElectionOptions = ExposedR2dbcLeaderGroupElectionOptions.Default,
        ): ExposedR2dbcSuspendLeaderGroupElection
    }

    override val maxLeaders: Int

    /**
     * non-suspend 계약. 최근 갱신된 캐시값 반환 (근사값).
     * runIfLeader 직전 내부에서 suspend 카운트 갱신.
     */
    override fun activeCount(lockName: String): Int
    override fun availableSlots(lockName: String): Int
    override fun state(lockName: String): LeaderGroupState

    override suspend fun <T> runIfLeader(
        lockName: String,
        action: suspend () -> T,
    ): T?
}
```

### 4.6 Options

```kotlin
package io.bluetape4k.leader.exposed.r2dbc

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.exposed.RetryStrategy
import java.io.Serializable

data class ExposedR2dbcLeaderElectionOptions(
    val leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default,
    val retryStrategy: RetryStrategy = RetryStrategy.Jitter(),
    val recordHistory: Boolean = false,
    val lockOwner: String? = null,
) : Serializable {

    init {
        lockOwner?.let {
            require(it.length <= ExposedLeaderConstants.LOCK_OWNER_LENGTH) {
                "lockOwner must be <= ${ExposedLeaderConstants.LOCK_OWNER_LENGTH} chars, but was ${it.length}"
            }
        }
    }

    companion object {
        @JvmField
        val Default = ExposedR2dbcLeaderElectionOptions()
    }
}

data class ExposedR2dbcLeaderGroupElectionOptions(
    val leaderGroupOptions: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    val retryStrategy: RetryStrategy = RetryStrategy.Jitter(),
    val recordHistory: Boolean = false,
    val lockOwner: String? = null,
) : Serializable {
    val maxLeaders: Int get() = leaderGroupOptions.maxLeaders

    init {
        require(maxLeaders > 0) { "maxLeaders must be positive: $maxLeaders" }
        lockOwner?.let {
            require(it.length <= ExposedLeaderConstants.LOCK_OWNER_LENGTH) {
                "lockOwner must be <= ${ExposedLeaderConstants.LOCK_OWNER_LENGTH} chars, but was ${it.length}"
            }
        }
    }

    companion object {
        @JvmField
        val Default = ExposedR2dbcLeaderGroupElectionOptions()
    }
}
```

### 4.7 Extension Functions

```kotlin
package io.bluetape4k.leader.exposed.r2dbc

import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

/**
 * R2dbcDatabase 확장 함수 -- 편의 API.
 */
suspend fun <T> R2dbcDatabase.suspendRunIfLeader(
    lockName: String,
    options: ExposedR2dbcLeaderElectionOptions = ExposedR2dbcLeaderElectionOptions.Default,
    action: suspend () -> T,
): T?

suspend fun <T> R2dbcDatabase.suspendRunIfLeaderGroup(
    lockName: String,
    options: ExposedR2dbcLeaderGroupElectionOptions = ExposedR2dbcLeaderGroupElectionOptions.Default,
    action: suspend () -> T,
): T?
```

---

## 5. 테스트 전략

### 5.1 테스트 인프라

| DB | 드라이버 | 설정 |
|---|---|---|
| H2 | `io.r2dbc:r2dbc-h2` | In-memory, 기본 동작 검증 |
| PostgreSQL 15 | `org.postgresql:r2dbc-postgresql` | Testcontainers `postgres:15-alpine`, 병행 경합 테스트 |
| MySQL 8 | `io.asyncer:r2dbc-mysql` (또는 `dev.miku:r2dbc-mysql`) | Testcontainers `mysql:8.0`, 3-DB 호환성 검증 |

### 5.2 테스트 베이스 클래스

```kotlin
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractExposedR2dbcLeaderTest {
    companion object : KLogging() {
        // Testcontainers -- PostgreSQL R2DBC
        private val pgContainer = PostgreSQLContainer("postgres:15-alpine")
        init { pgContainer.start() }

        fun pgR2dbcUrl(): String =
            "r2dbc:postgresql://${pgContainer.host}:${pgContainer.firstMappedPort}/${pgContainer.databaseName}"
        fun pgR2dbcUser(): String = pgContainer.username
        fun pgR2dbcPassword(): String = pgContainer.password
    }

    protected suspend fun connectR2dbc(/* DB 선택 파라미터 */): R2dbcDatabase
    protected suspend fun cleanTables(db: R2dbcDatabase)
    protected fun randomName(): String = "test-${UUID.randomUUID().toString().take(8)}"
}
```

### 5.3 테스트 시나리오

#### 단일 리더 (ExposedR2dbcSuspendLeaderElectionTest)
1. `기본 리더 획득 및 작업 실행` -- 락 획득 -> action 실행 -> 결과 반환
2. `경합 시 null 반환` -- 두 코루틴 동시 시도 -> 하나만 성공
3. `리스 만료 후 재획득` -- leaseTime 경과 후 새 인스턴스 획득 성공
4. `action 예외 시 락 해제` -- action 실패 -> 락 정상 반환
5. `코루틴 취소 시 CancellationException 재전파` -- cancel() -> 락 해제 + 예외 전파
6. `lockName 검증 실패` -- 잘못된 lockName -> IllegalArgumentException
7. `이력 기록 (recordHistory=true)` -- ACQUIRED -> COMPLETED/FAILED 기록 확인
8. `DB 오류 시 false 반환 (never-throws)` -- 트랜잭션 실패 -> null 반환

#### 그룹 리더 (ExposedR2dbcSuspendLeaderGroupElectionTest)
1. `최대 N개 동시 리더 허용` -- maxLeaders=3, 3개 동시 성공, 4번째 null
2. `슬롯 만료 후 재획득` -- leaseTime 경과 -> 슬롯 재사용
3. `랜덤 슬롯 시작` -- 핫스팟 방지 검증 (통계적)
4. `상태 조회 일관성` -- activeCount, availableSlots, state 반환값 검증

#### Lock 단위 테스트 (ExposedR2dbcLockTest)
1. `tryLock 성공/실패` -- 단일 lock의 acquire/release cycle
2. `토큰 불일치 unlock 무시` -- 다른 인스턴스의 unlock 시도 -> 무시
3. `재시도 전략 적용` -- Jitter/Exponential/Fixed 전략별 delay 검증

### 5.4 테스트 격리 및 코루틴 테스트 패턴
- 각 테스트 메서드 전에 `cleanTables(db)` 호출 (suspendTransaction 내 deleteAll)
- lockName은 `randomName()` (UUID 기반)으로 생성하여 테스트 간 간섭 방지
- `junit-platform.properties`에 `PER_CLASS` + `parallel=false` 설정
- **모든 suspend 테스트는 `runTest { }` 사용** (kotlinx-coroutines-test)
  - `runTest`는 가상 시간을 자동 진행하므로 `delay()` 호출이 실제 대기 없이 즉시 완료됨
  - leaseTime 만료 테스트에서 `advanceTimeBy(leaseTime.toMillis())` 사용
  - 주의: R2DBC 실제 I/O는 `Dispatchers.IO`에서 실행되므로, `delay()` 기반 retry가 가상 시간에서 즉시 완료되어 테스트 동작이 달라질 수 있음 → 필요 시 `advanceUntilIdle()` 사용

---

## 6. JDBC -> R2DBC 변환 매핑표

| JDBC | R2DBC |
|---|---|
| `transaction(db) { }` | `suspendTransaction(db) { }` |
| `Thread.sleep(ms)` | `delay(ms)` |
| `Database` | `R2dbcDatabase` (org.jetbrains.exposed.v1.r2dbc) |
| `Table.insert { }` | `Table.insert { }` (R2DBC DSL, 동일 문법) |
| `Table.update { }` | `Table.update { }` (R2DBC DSL) |
| `Table.deleteWhere { }` | `Table.deleteWhere { }` (R2DBC DSL) |
| `Table.selectAll().where { }` | `Table.selectAll().where { }` (R2DBC DSL) |
| `SchemaUtils.createMissingTablesAndColumns()` (org.jetbrains.exposed.v1.jdbc.SchemaUtils) | `SchemaUtils.createMissingTablesAndColumns()` (⚠️ `org.jetbrains.exposed.v1.r2dbc.SchemaUtils` -- within suspendTransaction) |
| `ReentrantLock` | `kotlinx.coroutines.sync.Mutex` |
| `Thread.currentThread().interrupt()` | N/A (코루틴은 CancellationException 사용) |
| `System.currentTimeMillis()` | `System.currentTimeMillis()` (동일) |
| `ConcurrentHashMap` | `ConcurrentHashMap` (코루틴에서도 thread-safe) |
| `KLogging()` | `KLoggingChannel()` (suspend 컨텍스트 지원) |

---

## 7. 의존성 그래프

```
leader-core (interfaces: SuspendLeaderElection, SuspendLeaderGroupElection)
    ^
    |
leader-exposed-core (tables + RetryStrategy[이동됨])
    ^
    |
leader-exposed-r2dbc (이 모듈)
    |
    +-- exposed-core, exposed-r2dbc, exposed-java-time
    +-- kotlinx-coroutines-core
    +-- bluetape4k-coroutines
    +-- r2dbc-postgresql (compileOnly)
    +-- r2dbc-h2 (compileOnly)
    +-- r2dbc-mysql (compileOnly, io.asyncer:r2dbc-mysql)
```

---

## 8. 구현 순서 (Task List)

| # | Task | 의존 | 예상 |
|---|---|---|---|
| 1 | RetryStrategy를 leader-exposed-core `io.bluetape4k.leader.exposed.retry` 패키지로 이동 + typealias. **추가**: `exposed-java-time` vs `exposed-kotlin-datetime` R2DBC 환경 호환성 검증 (`leader-exposed-core` 테이블이 `java.time.Instant` 사용 → `exposed-java-time` 유지 예상) | - | 0.5h |
| 2 | ExposedR2dbcSchemaInitializer 구현 | 1 | 0.5h |
| 3 | ExposedR2dbcLock 구현 (suspend tryLock/unlock). DB별 분기 로직 포함 (PostgreSQL: UPSERT/SAVEPOINT, H2/MySQL: runCatching). `build.gradle.kts` 직접 수정하여 `kotlinx.coroutines.core` api, `bluetape4k-coroutines` api 확인. | 1, 2 | 2h |
| 4 | ExposedR2dbcGroupLock 구현 | 3 | 1h |
| 5 | ExposedR2dbcLeaderElectionOptions / GroupOptions 작성 | 1 | 0.5h |
| 6 | ExposedR2dbcSuspendLeaderElection 구현 | 3, 5 | 1.5h |
| 7 | ExposedR2dbcSuspendLeaderGroupElection 구현 | 4, 5 | 1.5h |
| 8 | Extension functions 작성 | 6, 7 | 0.5h |
| 9 | AbstractExposedR2dbcLeaderTest 베이스 작성 | - | 1h |
| 10 | Lock 단위 테스트 (Lock + GroupLock + SchemaInitializer) | 2, 3, 4, 9 | 1.5h |
| 11 | Election 통합 테스트 (Single + Group) | 6, 7, 9 | 2h |
| 12 | 3-DB 호환성 검증 (H2 + PostgreSQL + MySQL) | 11 | 1h |
| 13 | build.gradle.kts 의존성 확정 + CI 검증: catalog에 `r2dbc-h2`(`io.r2dbc:r2dbc-h2`), `r2dbc-mysql`(`io.asyncer:r2dbc-mysql`) 추가. `kotlinx.coroutines.core` api 확인. `bluetape4k-coroutines` 확인. `exposed-java-time` vs `exposed-kotlin-datetime` 최종 확정. BOM(`leader-bom`) 등록 확인. | 12 | 1h |
| 14 | KDoc + README 업데이트 | 13 | 0.5h |

**Total**: ~13h

---

## Appendix A: SuspendLeaderGroupElection의 non-suspend 상태 조회 문제

`SuspendLeaderGroupElection`이 `LeaderGroupElectionState`를 상속하며, `activeCount()`, `availableSlots()`, `state()`가 non-suspend 시그니처입니다. R2DBC에서 이를 구현하려면:

**접근 1 (권장)**: 마지막 `runIfLeader` 호출 시 갱신한 캐시값 반환. 근사값이지만 인터페이스 계약(`근사값 반환 가능`)과 일치.

**접근 2**: `runBlocking` 사용. 단, 코루틴 컨텍스트 내 호출 시 데드락 위험이 있으므로 비권장.

**접근 3**: 인터페이스에 `suspend fun suspendActiveCount()` 추가. 기존 API 변경이므로 다음 마이너 버전에서 검토.

접근 1을 채택하고, 향후 인터페이스 개선 시 접근 3으로 마이그레이션합니다.

---

## Appendix B: PostgreSQL R2DBC INSERT PK 충돌 대응

PostgreSQL은 트랜잭션 내 에러 발생 시 해당 트랜잭션의 모든 후속 명령을 거부합니다 (`ERROR: current transaction is aborted`). JDBC에서는 `runCatching`으로 INSERT 예외를 흡수해도 트랜잭션은 유효한 상태를 유지하지만, PostgreSQL R2DBC에서는 트랜잭션이 abort됩니다.

**⚠️ SELECT-first의 TOCTOU 레이스 문제**: INSERT 전에 SELECT로 존재 확인 → INSERT 사이에 다른 노드가 INSERT 가능. SELECT-first는 추가 round-trip 비용 + 레이스 조건을 해결하지 못하므로 **PostgreSQL 1차 전략으로 채택 불가**.

**DB별 분기 전략 (확정)**:

| DB | INSERT 충돌 전략 | 근거 |
|---|---|---|
| **H2** | `runCatching { insert {} }` (JDBC 패턴 유지) | H2는 statement 실패가 트랜잭션을 abort하지 않음 |
| **MySQL 8** | `runCatching { insert {} }` (JDBC 패턴 유지) | MySQL은 statement 실패가 트랜잭션을 abort하지 않음 |
| **PostgreSQL** | `INSERT ... ON CONFLICT DO NOTHING` (UPSERT) 또는 `SAVEPOINT` 패턴 | 트랜잭션 abort 회피 필수 |

**PostgreSQL 구현 우선순위**:
1. **UPSERT (1차)**: Exposed R2DBC `upsert {}` 또는 `insertIgnore {}` 지원 확인 → 지원 시 가장 깔끔한 해법
2. **SAVEPOINT (2차)**: `exec("SAVEPOINT sp1")` → INSERT → 실패 시 `exec("ROLLBACK TO SAVEPOINT sp1")`. DB-specific raw SQL 필요하지만 확실
3. **SELECT-first (최후)**: 위 두 방법 모두 불가능한 경우에만 fallback으로 사용 (TOCTOU 레이스 인지하고 수용)

**구현 시 DB 방언 감지**:
```kotlin
// 의사코드
when (db.dialect) {
    is PostgreSQLDialect -> tryAcquireOnceWithUpsert(leaseTime)
    else -> tryAcquireOnceWithRunCatching(leaseTime)  // H2, MySQL
}
```

---

## Appendix C: leader-exposed-jdbc 개선 이슈 목록

R2DBC Spec 리뷰 과정에서 발견된 JDBC 모듈 개선 사항. 별도 GitHub Issue로 등록 검토.

### C.1 `ExposedJdbcGroupLock`에 `isHeldByCurrentInstance()` 누락

**현재 상태**: `ExposedJdbcLock`에는 `isHeldByCurrentInstance(): Boolean`이 존재하지만, `ExposedJdbcGroupLock`에는 **누락**됨.

**영향**: GroupLock 사용자가 장시간 action 중간에 lease 유효 여부를 확인할 수 없음 (zombie holder 시나리오에서 split-brain 감지 불가).

**권장 조치**: `ExposedJdbcGroupLock`에 동일한 `isHeldByCurrentInstance(): Boolean` 메서드 추가.
```kotlin
fun isHeldByCurrentInstance(): Boolean = runCatching {
    transaction(db) {
        val now = Instant.now()
        !LeaderGroupLockTable.selectAll().where {
            (LeaderGroupLockTable.lockName eq lockName) and
            (LeaderGroupLockTable.slot eq slot) and
            (LeaderGroupLockTable.token eq token) and
            (LeaderGroupLockTable.lockedUntil greater now)
        }.empty()
    }
}.getOrElse { false }
```

**Issue**: `feat: ExposedJdbcGroupLock에 isHeldByCurrentInstance() 추가`

### C.2 `KLogging` → `KLoggingChannel` 전환

**현재 상태**: JDBC 모듈의 모든 클래스가 `KLogging()`을 사용 중.

**영향**: JDBC 클래스는 blocking 컨텍스트이므로 `KLogging()`이 기능적으로 문제되지 않음. 단, `ExposedJdbcLeaderElection`이 `CancellationException`을 처리하며 코루틴 문맥에서 사용될 수 있으므로 `KLoggingChannel` 전환이 바람직.

**권장 조치**: **낮은 우선순위 (LOW)**. R2DBC와의 코드 일관성을 위해 JDBC 모듈도 `KLoggingChannel`로 전환 검토. 기능 변경은 아니므로 별도 리팩토링 PR에서 처리.

**Issue**: `refactor: leader-exposed-jdbc KLogging → KLoggingChannel 전환`

### C.3 GroupLock 슬롯 순회 DB 오류 vs 경합 실패 구별

**현재 상태**: `ExposedJdbcLeaderGroupElection.runIfLeader`의 슬롯 순회에서 `lock.tryLock()`이 `false`를 반환하면 다음 슬롯으로 넘어감. DB 오류(connection pool 고갈 등)와 정상 경합 실패를 구별하지 않음.

**영향**: connection pool 고갈 같은 치명적 오류에서도 모든 슬롯을 순회하며 무의미한 재시도 수행. 총 `maxLeaders * perSlotWait` 시간 낭비.

**권장 조치**: `ExposedJdbcGroupLock.tryLock()`이 DB 오류 시 `Result<Boolean>` 반환 또는 특수 예외 throw → Election에서 즉시 종료. 기존 API 변경이므로 마이너 버전에서 검토.

**Issue**: `feat: ExposedJdbcGroupLock 슬롯 순회 DB 오류 시 즉시 종료 지원`
