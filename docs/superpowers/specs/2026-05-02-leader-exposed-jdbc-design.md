# leader-exposed-jdbc 설계 스펙

- 작성일: 2026-05-02
- 모듈: `leader-exposed-jdbc`
- 워크트리: `.worktrees/feat/issue-21-exposed-jdbc`
- 상태: v3-final (Codex + v3 Critic 모든 CRITICAL/HIGH 수정 완료)
- 베이스 브랜치: `develop`
- 선행 모듈: `leader-exposed-core` (스키마 정의 — 이미 구현 완료)
- 참조 구현: `leader-mongodb` (MongoLock 패턴)

---

## 1. 개요

### 1.1 목적

`leader-exposed-jdbc`는 JetBrains Exposed JDBC를 이용한 RDBMS 기반 분산 리더 선출 구현체를 제공합니다.
`leader-exposed-core`에 정의된 테이블 스키마(`LeaderLockTable`, `LeaderGroupLockTable`, `LeaderLockHistoryTable`)를
활용하여, `leader-mongodb`와 동일한 인터페이스 계약을 JDBC 트랜잭션 기반으로 충족합니다.

### 1.2 구현 대상 인터페이스

| 구현 클래스 | 인터페이스 | 설명 |
|---|---|---|
| `ExposedJdbcLeaderElection` | `LeaderElection` + `AsyncLeaderElection` | 단일 리더 선출 (동기 + CF 비동기) |
| `ExposedJdbcLeaderGroupElection` | `LeaderGroupElection` + `AsyncLeaderGroupElection` | 복수 리더 그룹 선출 |
| `ExposedJdbcVirtualThreadLeaderElection` | `VirtualThreadLeaderElection` | VirtualThread 기반 비동기 선출 |

### 1.3 비목표 (Non-goals)

- `SuspendLeaderElection` / `SuspendLeaderGroupElection` — R2DBC 모듈(`leader-exposed-r2dbc`)에서 구현
- 자동 lease renewal — action 실행 중 lease 자동 갱신 없음. `leaseTime`은 action 최대 실행 시간보다 충분히 크게 설정해야 함 (권장: p99 실행시간 x 2 이상)
- 분산 트랜잭션 (XA) — 단일 DB 인스턴스 내 트랜잭션만 사용
- Spring `@Transactional` 통합 — 독립 `transaction {}` 블록 사용

---

## 2. 모듈 의존성

### 2.1 의존성 그래프

```
leader-core (인터페이스)
    ↓
leader-exposed-core (스키마: LeaderLockTable, LeaderGroupLockTable, LeaderLockHistoryTable)
    ↓
leader-exposed-jdbc (이 모듈: JDBC 구현)
```

### 2.2 build.gradle.kts (현재 상태)

```kotlin
dependencies {
    api(project(":leader-core"))
    api(project(":leader-exposed-core"))

    // Exposed JDBC
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    api(libs.exposed.dao)

    // Connection pool + PostgreSQL driver
    implementation(libs.hikaricp)
    compileOnly(libs.postgresql)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.postgresql)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
}
```

### 2.3 누락 의존성 (추가 필요)

현재 `build.gradle.kts`에 **H2**와 **MySQL** 테스트 의존성이 누락되어 있습니다.
3-DB 파라미터화 테스트를 위해 다음 의존성을 추가해야 합니다:

```kotlin
// H2 (in-memory, 빠른 단위 테스트)
testImplementation(libs.h2.v2)

// MySQL (Testcontainers)
testImplementation(libs.mysql.connector.j)
testImplementation(libs.testcontainers.mysql)

// exposed-java-time (timestamp 컬럼 DSL)
api(libs.exposed.java.time)

// bluetape4k exposed JDBC 테스트 유틸리티 (TestDB, withDb, withTables)
testImplementation(libs.bluetape4k.exposed.jdbc.tests)

// bluetape4k-exposed-jdbc (VirtualThread 아님 — virtualFuture는 bluetape4k.core 전이 의존성으로 제공됨)
// 이 의존성은 실제 필요한 API가 확인된 경우에만 추가
// api(libs.bluetape4k.exposed.jdbc)  // ← 현재 불필요, 제거
```

**근거**: `leader-exposed-core/build.gradle.kts`가 H2 + MySQL + `bluetape4k.exposed.jdbc.tests`를 포함하고 있으며,
`AbstractExposedTableTest`의 `enableDialects()`가 H2, POSTGRESQL, MYSQL_V8을 반환합니다.
동일한 3-DB 테스트 매트릭스를 사용해야 합니다.

> **[Codex M6]** `virtualFuture`/`VirtualFuture`는 `bluetape4k.core` → `leader-core` 전이 의존성으로 제공. `bluetape4k.exposed.jdbc` 별도 추가 불필요.

---

## 3. 핵심 설계 결정

### 3.1 락 획득 흐름 (단계별 SQL)

**결정: Option B — `UPDATE WHERE expired` + `INSERT fallback` (2-step 패턴)**

> **[C1 수정]** 초안의 Option C(upsert WHERE)는 MySQL/H2에서 `UnsupportedOperationException`을 던집니다.
> Exposed 1.2.0에서 `upsert()` `WHERE` 절은 PostgreSQL만 지원합니다 (MySQL: `throwUnsupportedException`, H2: `throwUnsupportedException`).
> 3-DB 호환성을 보장하기 위해 Option B로 변경합니다.

#### 접근법 비교

| | Option A: `SELECT FOR UPDATE SKIP LOCKED` | **Option B: `UPDATE WHERE expired` + `INSERT fallback`** | Option C: `upsert() WHERE` DSL |
|---|---|---|---|
| 호환성 | PostgreSQL 전용 (H2 미지원) | **H2/PG/MySQL 모두 동작** | PostgreSQL만 WHERE 지원; MySQL/H2는 런타임 예외 |
| 락 경합 성능 | SKIP LOCKED로 대기 없이 pass | UPDATE 실패 시 INSERT 재시도 | — |
| 구현 복잡도 | DB별 분기 필요 | **단일 패턴 (2 SQL)** | 단순하나 DB 제약으로 3-DB에서 동작 불가 |
| 안전 속성 | 유효한 락 건드리지 않음 | **UPDATE WHERE 조건으로 유효한 락 보존** | upsert WITHOUT WHERE는 유효한 락도 덮어씀 |
| 적합성 | 고경합 환경에 최적 | **분산 리더 선출 + 3-DB 호환 + KISS** | 3-DB 요구사항 불충족 |

**최종 결정: Option B — `UPDATE WHERE expired` + `INSERT fallback`**

단일 트랜잭션 내에서:
1. `UPDATE WHERE (lockName = ? AND lockedUntil < now)` — 만료된 락을 내 token으로 갱신
2. UPDATE affected rows = 0이면 `INSERT` — 신규 락 삽입 (PK 충돌 = 다른 인스턴스가 보유 중)
3. `SELECT WHERE lockName = ?` — token 검증으로 최종 획득 여부 확인

이 패턴은 MongoDB의 `findOneAndUpdate(upsert=true)` 동작과 의미적으로 동등합니다 (Appendix A 참조).

#### Option A, C 기각 이유

- **Option A**: H2 미지원 + 고경합이 아닌 환경에서 SKIP LOCKED 이점 미미 + DB별 분기 코드
- **Option C**: MySQL/H2에서 `UnsupportedOperationException` 런타임 오류 — 3-DB 요구사항 미충족

#### 단일 리더 락 획득 시퀀스 (ExposedJdbcLock.tryLock — UPDATE+INSERT 방식)

```
// ExposedJdbcLock 인스턴스 생성 시 token 1회 발급 (MongoDB MongoLock과 동일)
// val token = Base58.randomString(8)  ← 인스턴스 필드, 매 시도마다 재생성하지 않음

┌─ deadline = now + waitTime ──────────────────────────────────────────┐
│                                                                        │
│  var attempt = 0                                                       │
│  do {                                                                  │
│    // 트랜잭션 시작 (기본 격리: READ_COMMITTED)                          │
│    val acquired = transaction(db) {                                    │
│      val now = Instant.now()                                           │
│                                                                        │
│      // Step 1: 만료된 락 갱신 시도 (내 token으로)                       │
│      val updated = LeaderLockTable.update(                             │
│        where = { (lockName eq name) and (lockedUntil less now) }       │
│      ) {                                                               │
│        it[token] = this@ExposedJdbcLock.token  // 인스턴스 필드        │
│        it[lockedAt] = now                                              │
│        it[lockedUntil] = now + leaseTime                               │
│      }                                                                 │
│                                                                        │
│      if (updated == 0) {                                               │
│        // Step 2: 레코드 없음 → 신규 삽입 (PK 충돌 시 catch)             │
│        runCatching {                                                   │
│          LeaderLockTable.insert {                                       │
│            it[lockName] = name                                         │
│            it[token] = this@ExposedJdbcLock.token  // 인스턴스 필드   │
│            it[lockedAt] = now                                          │
│            it[lockedUntil] = now + leaseTime                           │
│          }                                                             │
│        }.onFailure { /* PK 충돌 = 타인이 보유 중 */ return@transaction false }
│      }                                                                 │
│                                                                        │
│      // Step 3: token 검증 SELECT                                      │
│      val storedToken = LeaderLockTable                                 │
│        .select(LeaderLockTable.token)                                  │
│        .where { LeaderLockTable.lockName eq name }                     │
│        .singleOrNull()?.get(LeaderLockTable.token)                     │
│                                                                        │
│      storedToken == token  // 인스턴스 token과 일치하면 획득 성공          │
│    } // 트랜잭션 종료 — connection 반납                                   │
│                                                                        │
│    if (acquired) return true                                           │
│    val remaining = deadline.toEpochMilli() - Instant.now().toEpochMilli()
│    if (remaining <= 0) break                                           │
│    Thread.sleep(retryStrategy.delayMs(attempt++, remaining))  // 트랜잭션 바깥에서 sleep
│  } while (Instant.now() < deadline)                                    │
│                                                                        │
│  return false  (타임아웃)                                               │
└────────────────────────────────────────────────────────────────────────┘
```

**핵심 포인트**:
- **token 1개 원칙**: 인스턴스 생성 시 1회 발급. `tryLock` 성공 후 `unlock`/`isHeldByCurrentInstance`/`history`가 동일 token 사용 (MongoDB 패턴 동일)
- UPDATE + INSERT + SELECT가 **단일 `transaction {}` 블록** 내에서 실행됨 (원자성 보장)
- `Thread.sleep(retryStrategy.delayMs(...))`는 **트랜잭션 블록 바깥**에서 실행 → connection 반납 후 sleep (HikariCP 풀 고갈 방지)
- `UPDATE WHERE lockedUntil < now`로 유효한 락은 절대 덮어쓰지 않음 (안전 속성 보장)
- PK 충돌은 `runCatching`으로 포착 → retry 루프 계속

#### 락 해제 시퀀스 (ExposedJdbcLock.unlock)

```kotlin
transaction {
    val deleted = LeaderLockTable.deleteWhere {
        (lockName eq name) and (token eq currentToken)
    }
    if (deleted == 0) {
        log.warn { "락 해제 실패 — 토큰 불일치 또는 이미 만료됨: lockName=$name" }
    }
}
```

token 기반 `deleteWhere`로 zombie unlock을 방지합니다.
lease 만료 후 다른 인스턴스가 재획득한 경우, 원 소유자의 unlock은 새 소유자의 락을 건드리지 않습니다.

### 3.2 그룹 락 슬롯 순회 전략

MongoDB 패턴과 동일하게, `${lockName}` + `slot(0..maxLeaders-1)` 복합 키를 사용합니다.

#### 슬롯 순회 알고리즘

```
start = Random.nextInt(maxLeaders)  // 핫스팟 방지
perSlotWait = waitTime / maxLeaders

for i in 0 until maxLeaders:
    slot = (start + i) % maxLeaders
    lock = ExposedJdbcGroupLock(db, lockName, slot)
    if lock.tryLock(perSlotWait, leaseTime):
        try:
            return action()
        finally:
            lock.unlock()

return null  // 모든 슬롯 획득 실패
```

**접근법 비교**:

| | Option A: 순차 순회 (slot 0, 1, 2, ...) | Option B: 랜덤 시작 순회 (MongoDB 패턴) |
|---|---|---|
| 핫스팟 | slot 0에 경합 집중 | 분산 |
| 구현 복잡도 | 최소 | `Random.nextInt` 한 줄 추가 |
| 공정성 | 낮음 (항상 slot 0 먼저) | 높음 |

**결정: Option B (랜덤 시작 순회)** — MongoDB 검증 패턴이며, 핫스팟 방지 효과가 비용 대비 우수.

#### 그룹 락 SQL (ExposedJdbcGroupLock — UPDATE+INSERT 방식)

단일 리더 락과 동일한 패턴. `LeaderGroupLockTable` 사용, `(lockName, slot)` 복합 PK.

```kotlin
// ExposedJdbcGroupLock 인스턴스 생성 시 token 1회 발급 (인스턴스 필드, 매 시도마다 재생성하지 않음)
// val token = Base58.randomString(8)  ← Section 4.2 참조

// tryLock — Step 1: 만료된 슬롯 갱신 (인스턴스 token 사용)
val updated = LeaderGroupLockTable.update(
    where = {
        (LeaderGroupLockTable.lockName eq name) and
        (LeaderGroupLockTable.slot eq slotNumber) and
        (LeaderGroupLockTable.lockedUntil less now)
    }
) {
    it[token]       = this@ExposedJdbcGroupLock.token  // 인스턴스 필드
    it[lockOwner]   = owner
    it[lockedAt]    = now
    it[lockedUntil] = now + leaseTime
}

// tryLock — Step 2: 레코드 없으면 신규 삽입
if (updated == 0) {
    runCatching {
        LeaderGroupLockTable.insert {
            it[lockName]    = name
            it[slot]        = slotNumber
            it[lockOwner]   = owner
            it[token]       = this@ExposedJdbcGroupLock.token  // 인스턴스 필드
            it[lockedAt]    = now
            it[lockedUntil] = now + leaseTime
        }
    }.onFailure { return@transaction false }  // 복합 PK 충돌
}

// tryLock — Step 3: token 검증 (인스턴스 token과 일치하면 획득 성공)
val storedToken = LeaderGroupLockTable
    .select(LeaderGroupLockTable.token)
    .where { (LeaderGroupLockTable.lockName eq name) and (LeaderGroupLockTable.slot eq slotNumber) }
    .singleOrNull()?.get(LeaderGroupLockTable.token)
// storedToken == token → 획득 성공 (인스턴스 token 비교)

// unlock — fencing token 조건부 삭제
LeaderGroupLockTable.deleteWhere {
    (lockName eq name) and (slot eq slotNumber) and (token eq currentToken)
}
```

### 3.3 이력 기록 패턴

**결정: 선택적 (옵션 `recordHistory: Boolean = false`)**

#### 접근법 비교

| | Option A: 항상 기록 | Option B: 선택적 (기본 비활성) | Option C: 이력 미지원 |
|---|---|---|---|
| 성능 오버헤드 | INSERT 1회/선출 | 0 (비활성 시) | 0 |
| 관찰성 | 최고 | 필요 시 활성화 | 없음 |
| 테이블 크기 | 빠르게 증가 | 활성화한 경우에만 | N/A |

**결정: Option B** — `LeaderLockHistoryTable`이 `leader-exposed-core`에 이미 존재하므로 구조적 지원은 갖추었으나,
기본 비활성화하여 성능 오버헤드를 방지합니다.

#### 이력 기록 흐름

> **[Codex H3 수정]** `historyId`를 transaction 바깥으로 반환. action은 `try/catch/finally` 계약으로 명시. audit 실패 정책: **best-effort** (audit 저장 실패가 리더 실행을 실패시키지 않음).

```kotlin
// recordHistory = true일 때만 실행
// historyId는 transaction 밖의 지역 변수 (스코프 문제 해결)
val historyId: Long? = if (options.recordHistory) {
    // 1. ACQUIRED 이력 삽입 (action 실행 전, 별도 트랜잭션)
    runCatching {
        transaction(db) {
            LeaderLockHistoryTable.insert {
                it[lockName] = name
                it[lockOwner] = owner
                it[token] = lock.token   // 인스턴스 token (수명 통일)
                it[slot] = slotNumber    // 그룹 락이면 슬롯 번호, 단일 락이면 null
                it[lockedUntil] = expireAt
                it[status] = HistoryStatus.ACQUIRED.name
                it[startedAt] = startedAt
            } get LeaderLockHistoryTable.id
        }
    }.getOrElse { e ->
        log.warn(e) { "이력 ACQUIRED 기록 실패 (best-effort). lockName=$name" }
        null  // audit 실패는 리더 실행을 막지 않음
    }
} else null

// 2. action 실행 — try/catch/finally 계약
var actionFailed = false
val result = try {
    action()
} catch (e: CancellationException) {
    throw e  // CancellationException은 actionFailed 미설정 + 즉시 재전파 (코루틴 취소 계약)
} catch (e: Throwable) {
    actionFailed = true
    throw e  // 애플리케이션 예외 재전파
} finally {
    // 3. COMPLETED/FAILED 이력 업데이트 (action 완료/실패 후, best-effort)
    if (historyId != null) {
        val finishedAt = Instant.now()
        runCatching {
            transaction(db) {
                LeaderLockHistoryTable.update(
                    where = { LeaderLockHistoryTable.id eq historyId }
                ) {
                    it[status] = if (actionFailed) HistoryStatus.FAILED.name else HistoryStatus.COMPLETED.name
                    it[this.finishedAt] = finishedAt
                    it[durationMs] = finishedAt.toEpochMilli() - startedAt.toEpochMilli()
                }
            }
        }.onFailure { e -> log.warn(e) { "이력 완료 기록 실패 (best-effort). lockName=$name" } }
    }
}
```

**audit 실패 정책: best-effort**
- ACQUIRED/COMPLETED/FAILED 이력 INSERT/UPDATE 실패는 warn 로그만 남기고 무시
- `runIfLeader` never-throws 계약을 audit 실패로 깨지 않음
- `actionFailed` 플래그로 `finally` 블록에서 action 예외 여부 판별

### 3.4 재시도 전략 (RetryStrategy)

**결정: `RetryStrategy` sealed class — jitter / exponential / fixed 옵션으로 선택 가능 (사용자 명시 결정)**

#### RetryStrategy 설계

> **[Codex M5 수정]** `nextDelayMs` → `delayMs`. `sleep`은 전략에서 제거, 호출부에서 `Thread.sleep(retryStrategy.delayMs(...))` 사용. Jitter 프로퍼티와 메서드 파라미터 중복 제거.

```kotlin
sealed class RetryStrategy {
    /** 다음 대기 시간(ms) 반환. 호출부에서 Thread.sleep()으로 실행. */
    abstract fun delayMs(attempt: Int, remaining: Long): Long

    /** AWS full jitter: [1ms, baseDelayMs) 균등 분포 — thundering herd 방지 (기본값) */
    data class Jitter(val baseDelayMs: Long = 50L) : RetryStrategy() {
        override fun delayMs(attempt: Int, remaining: Long): Long =
            ThreadLocalRandom.current().nextLong(1, baseDelayMs.coerceAtLeast(2))
                .coerceAtMost(remaining)
    }

    /** 지수 백오프: min(baseDelayMs * 2^attempt, maxDelayMs) — 드문 재시도에 유리 */
    data class Exponential(val baseDelayMs: Long = 50L, val maxDelayMs: Long = 5_000L) : RetryStrategy() {
        override fun delayMs(attempt: Int, remaining: Long): Long =
            (baseDelayMs * (1L shl attempt.coerceAtMost(10)))
                .coerceAtMost(maxDelayMs)
                .coerceAtMost(remaining)
    }

    /** 고정 간격: 항상 fixedMs 대기 — 단순/예측 가능 */
    data class Fixed(val fixedMs: Long = 50L) : RetryStrategy() {
        override fun delayMs(attempt: Int, remaining: Long): Long =
            fixedMs.coerceAtMost(remaining)
    }
}
```

**사용 패턴** (호출부):
```kotlin
// tryLock 내부 retry 루프
Thread.sleep(retryStrategy.delayMs(attempt++, remaining))
```

#### 전략별 비교

| 전략 | 적합한 상황 | 기본값 |
|------|-----------|--------|
| `Jitter` | 다수 인스턴스 경합 — thundering herd 방지 | ✅ (기본값) |
| `Exponential` | 경합이 드물고 빠른 초기 재시도 + 점진적 백오프 필요 | - |
| `Fixed` | 단순 환경, 예측 가능한 재시도 간격 필요 | - |

#### 옵션 통합

`RetryStrategy`는 `ExposedJdbcLeaderElectionOptions`에 포함:
```kotlin
data class ExposedJdbcLeaderElectionOptions(
    val leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default,
    val retryStrategy: RetryStrategy = RetryStrategy.Jitter(baseDelayMs = 50L),
    val recordHistory: Boolean = false,
    val lockOwner: String = defaultLockOwner(),
)
```

### 3.5 VirtualThread 구현 전략

**결정: Delegate 패턴 — `ExposedJdbcLeaderElection`을 `virtualFuture { }` 로 래핑**

> **[H1 수정]** 초안에서 `virtualThreadJdbcTransactionAsync { }` 내부에서 `ExposedJdbcLock.tryLock()`을 호출하면
> `tryLock()` 내부의 `transaction(db) {}` 블록과 중첩 트랜잭션이 발생합니다.
> Section 4.5의 Delegate 패턴이 올바른 구현입니다.
>
> `ExposedJdbcLeaderElection.runIfLeader()`는 이미 blocking JDBC이므로,
> `virtualFuture { delegate.runIfLeader(...) }` 로 VirtualThread에서 실행하는 것으로 충분합니다.

구체적인 구현은 **Section 4.5** 참조. Section 4.5가 권위 있는 유일한 구현 소스입니다.

`bluetape4k-exposed-jdbc`의 `virtualFuture { }` 유틸리티:
- `io.github.bluetape4k:bluetape4k-exposed-jdbc` 제공
- JDBC 블로킹 I/O를 VirtualThread에서 실행 → carrier thread 반납 → 플랫폼 스레드 풀 효율 증가
- `VirtualFuture<T?>` 반환으로 `VirtualThreadLeaderElection` 인터페이스 계약 충족

#### virtualFuture 의존성 경로

> **[Codex M6 수정]** `virtualFuture`/`VirtualFuture`는 `io.bluetape4k.concurrent.virtualthread` 패키지에 있으며,
> `bluetape4k-core`가 제공합니다 (`io.github.bluetape4k:bluetape4k-core`).
> `leader-core`가 `api(libs.bluetape4k.core)`로 노출하므로 `leader-exposed-jdbc`는 전이 의존성으로 이미 사용 가능합니다.
> **별도 의존성 추가 불필요**.

```kotlin
// leader-core → api(libs.bluetape4k.core) → virtualFuture, VirtualFuture 전이 노출됨
// leader-exposed-jdbc: api(project(":leader-core")) 에서 자동 제공

// import io.bluetape4k.concurrent.virtualthread.VirtualFuture
// import io.bluetape4k.concurrent.virtualthread.virtualFuture
```

Section 2.3의 `bluetape4k.exposed.jdbc` 의존성은 **VirtualThread 때문이 아닌 다른 필요성이 없으면 제거합니다**.

---

## 4. 구현 대상 클래스 상세

### 4.1 ExposedJdbcLock (단일 락 클래스)

MongoDB의 `MongoLock` 대응. 단위 테스트 가능한 순수 락 클래스.

```kotlin
class ExposedJdbcLock(
    private val db: Database,
    val lockName: String,
    private val retryStrategy: RetryStrategy = RetryStrategy.Jitter(baseDelayMs = 50L),
) {
    private val token: String = Base58.randomString(8)

    fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean
    fun isHeldByCurrentInstance(): Boolean
    fun unlock()
}
```

**핵심 설계**:
- `token` 기반 fencing: 생성 시 UUID 발급, unlock 시 `WHERE token = ?` 조건
- `Database` 의존: Exposed `transaction(db) {}` 블록 내에서 SQL 실행
- 재시도 루프: `tryLock` 내부에서 deadline까지 UPDATE → INSERT → `Thread.sleep(retryStrategy.delayMs(attempt, remaining))` 반복 (sleep은 트랜잭션 바깥)
- 예외 처리: PK 충돌은 `runCatching`으로 포착 후 재시도, DB 연결 오류는 `false` 반환 + warn 로그

### 4.2 ExposedJdbcGroupLock (그룹 락 클래스)

단일 슬롯 락. `ExposedJdbcLock`과 유사하나 `LeaderGroupLockTable` 사용, `(lockName, slot)` 복합 키.

```kotlin
class ExposedJdbcGroupLock(
    private val db: Database,
    val lockName: String,
    val slot: Int,                                                        // 0 until maxLeaders
    private val retryStrategy: RetryStrategy = RetryStrategy.Jitter(baseDelayMs = 50L),
) {
    private val token: String = Base58.randomString(8)

    fun tryLock(waitTime: Duration, leaseTime: Duration): Boolean
    fun unlock()
}
```

### 4.3 ExposedJdbcLeaderElection

> **[Codex H2 수정]** 생성자를 `private`으로 강제. 모든 진입점(확장 함수 포함)이 반드시 `invoke` 팩토리를 통과하여 `ensureSchema` 보장.

```kotlin
class ExposedJdbcLeaderElection private constructor(  // private — 직접 생성 금지
    private val db: Database,
    val options: ExposedJdbcLeaderElectionOptions = ExposedJdbcLeaderElectionOptions.Default,
) : LeaderElection {

    companion object : KLogging() {
        operator fun invoke(
            db: Database,
            options: ExposedJdbcLeaderElectionOptions = ExposedJdbcLeaderElectionOptions.Default,
        ): ExposedJdbcLeaderElection {
            ensureSchema(db)                          // 항상 보장
            return ExposedJdbcLeaderElection(db, options)
        }
    }

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? { ... }
    override fun <T> runAsyncIfLeader(lockName: String, executor: Executor, action: () -> CompletableFuture<T>): CompletableFuture<T?> { ... }
}
```

**패턴**: MongoDB `MongoLeaderElection`과 1:1 대응.
- 생성자 `private` → 외부에서 직접 생성 불가. 반드시 `ExposedJdbcLeaderElection(db, options)` (invoke) 경유
- 확장 함수(`Database.runIfLeader`)도 `ExposedJdbcLeaderElection(this, options)` → 내부적으로 `invoke` 호출 (스키마 초기화 보장)
- `runIfLeader`: lockName 검증 → `ExposedJdbcLock.tryLock` → action 실행 → `finally { lock.unlock() }`
- `runAsyncIfLeader`: `CompletableFuture.supplyAsync` + `thenComposeAsync` 체인 (MongoDB 패턴 동일)

### 4.4 ExposedJdbcLeaderGroupElection

> **[Codex H2 수정 + v3 Critic H2 수정]** 동일: `private constructor` + `companion invoke`로 `ensureSchema` 보장.
> Section 4.3과 동등한 팩토리 패턴 적용.

```kotlin
class ExposedJdbcLeaderGroupElection private constructor(  // private — 직접 생성 금지
    private val db: Database,
    val options: ExposedJdbcLeaderGroupElectionOptions = ExposedJdbcLeaderGroupElectionOptions.Default,
) : LeaderGroupElection {

    companion object : KLogging() {
        operator fun invoke(
            db: Database,
            options: ExposedJdbcLeaderGroupElectionOptions = ExposedJdbcLeaderGroupElectionOptions.Default,
        ): ExposedJdbcLeaderGroupElection {
            ensureSchema(db)                              // 항상 보장 (Section 4.3과 동일)
            return ExposedJdbcLeaderGroupElection(db, options)
        }
    }

    override val maxLeaders: Int get() = options.maxLeaders

    override fun activeCount(lockName: String): Int { ... }
    override fun availableSlots(lockName: String): Int { ... }
    override fun state(lockName: String): LeaderGroupState { ... }

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? { ... }
    override fun <T> runAsyncIfLeader(lockName: String, executor: Executor, action: () -> CompletableFuture<T>): CompletableFuture<T?> { ... }
}
```

**`activeCount` 구현**:
```kotlin
transaction(db) {
    LeaderGroupLockTable.selectAll()
        .where {
            (LeaderGroupLockTable.lockName eq lockName) and
            (LeaderGroupLockTable.lockedUntil greater Instant.now())
        }
        .count().toInt()
}
```

### 4.5 ExposedJdbcVirtualThreadLeaderElection

> **[v3 Critic H4 수정]** `private constructor` + `invoke` 패턴이 **의도적으로 생략됨**.
> `delegate: ExposedJdbcLeaderElection`은 이미 `ExposedJdbcLeaderElection.invoke()`를 통해 생성되므로
> `ensureSchema`가 이미 보장된 상태입니다. 이 클래스가 스키마를 재초기화할 이유가 없으며,
> 팩토리 래퍼를 추가하면 불필요한 간접 레이어가 생깁니다.
> `ExposedJdbcVirtualThreadLeaderElection(election)` 형태의 직접 생성이 올바른 사용법입니다.

```kotlin
// private constructor 없음 — delegate가 이미 invoke()로 생성됨 (ensureSchema 재호출 불필요)
class ExposedJdbcVirtualThreadLeaderElection(
    private val delegate: ExposedJdbcLeaderElection,
) : VirtualThreadLeaderElection {

    override fun <T> runAsyncIfLeader(lockName: String, action: () -> T): VirtualFuture<T?> =
        virtualFuture {
            delegate.runIfLeader(lockName, action)
        }
}
```

Delegate 패턴. 생성 시 `ExposedJdbcLeaderElection` 인스턴스를 받아 `virtualFuture`로 래핑.
`ExposedJdbcLeaderElection`이 이미 스키마 초기화와 옵션 검증을 완료한 상태이므로 중복 초기화 없음.

---

## 5. 옵션 클래스

### 5.1 ExposedJdbcLeaderElectionOptions

> **[H2 수정]** `retryDelay: Duration` → `retryStrategy: RetryStrategy`로 통일.
> **[H4 수정]** `lockOwner` 길이 검증 추가 (VARCHAR 255 초과 시 never-throws 계약 위반 방지).

```kotlin
data class ExposedJdbcLeaderElectionOptions(
    val leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default,
    val retryStrategy: RetryStrategy = RetryStrategy.Jitter(baseDelayMs = 50L),
    val recordHistory: Boolean = false,
    val lockOwner: String? = null,
) : Serializable {
    init {
        lockOwner?.let {
            require(it.length <= 255) { "lockOwner too long (max 255): ${it.length}" }
        }
    }

    companion object {
        @JvmField
        val Default = ExposedJdbcLeaderElectionOptions()
    }
}
```

### 5.2 ExposedJdbcLeaderGroupElectionOptions

> **[H2 수정]** `retryDelay: Duration` → `retryStrategy: RetryStrategy`로 통일.
> **[H4 수정]** `lockOwner` 길이 검증 추가.

```kotlin
data class ExposedJdbcLeaderGroupElectionOptions(
    val leaderGroupOptions: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    val retryStrategy: RetryStrategy = RetryStrategy.Jitter(baseDelayMs = 50L),
    val recordHistory: Boolean = false,
    val lockOwner: String? = null,
) : Serializable {
    val maxLeaders: Int get() = leaderGroupOptions.maxLeaders

    init {
        require(maxLeaders > 0) { "maxLeaders must be positive: $maxLeaders" }
        lockOwner?.let {
            require(it.length <= 255) { "lockOwner too long (max 255): ${it.length}" }
        }
    }

    companion object {
        @JvmField
        val Default = ExposedJdbcLeaderGroupElectionOptions()
    }
}
```

**`lockOwner`**: 선택적 인스턴스 식별자 (hostname, pod name 등).
`LeaderLockTable.lockOwner` 컬럼에 저장 (VARCHAR 255). 디버깅/관찰성 용도. `null`이면 미설정.
255자 초과 시 생성자에서 `IllegalArgumentException` — never-throws 계약은 `runIfLeader` 실행 중 발생하는 예외에만 적용됩니다 (Section 7).

---

## 6. 스키마 초기화

### 6.1 ensureSchema 패턴

```kotlin
private val schemaInitialized = ConcurrentHashMap<String, Boolean>()

fun ensureSchema(db: Database) {
    val key = db.url  // 동일 DB URL에 대해 1회만 실행
    schemaInitialized.computeIfAbsent(key) {
        transaction(db) {
            SchemaUtils.createMissingTablesAndColumns(*ExposedLeaderSchema.allTables)
        }
        true
    }
}
```

`ExposedLeaderSchema.allTables`는 `leader-exposed-core`에서 제공하는 `[LeaderLockTable, LeaderGroupLockTable, LeaderLockHistoryTable]` 배열입니다.

---

## 7. 인터페이스 계약 (runIfLeader never-throws)

> **[Codex M4 수정]** lock 계층(Boolean)과 election 계층(T?) 반환값 분리.

### Lock 계층 (`ExposedJdbcLock.tryLock(): Boolean`)

| 결과 상황 | 반환값 |
|---|---|
| 락 획득 성공 | `true` |
| 락 획득 실패 (`waitTime` 초과) | `false` |
| PK 충돌 (재시도 후 타임아웃) | `false` |
| DB 연결 오류 등 SQL 예외 | `false` + warn 로그 (재시도 없음) |

### Election 계층 (`ExposedJdbcLeaderElection.runIfLeader(): T?`)

| 결과 상황 | 반환값 / 동작 |
|---|---|
| `tryLock() == true` → action 정상 종료 | `action()` 의 반환값 (`T`) |
| `tryLock() == false` (락 미획득) | `null` |
| `action()` 내부에서 throw | 예외 전파, `finally { lock.unlock() }` |
| `lockName.isBlank()` | `IllegalArgumentException` (validate 단계에서 즉시) |
| `lockName` 규칙 위반 | `IllegalArgumentException` (공통 `validateLockName()`) |

**MongoDB와의 차이**: MongoDB는 `MongoCommandException`/`MongoWriteException` 등 세분화된 예외 분기가 필요하지만,
JDBC/Exposed는 `ExposedSQLException` 한 가지로 통합됩니다. PK 충돌 여부는 vendor-specific error code로 판별합니다.

---

## 8. 위험 식별 및 완화 전략

### 8.1 위험 1: DB Clock Drift

**위험**: 분산 환경에서 DB 서버와 애플리케이션 서버의 시계가 다를 경우, `lockedUntil < NOW()` 비교가 부정확해질 수 있음.

**완화**:
- `NOW()`는 DB 서버 시간 사용 (Exposed `CurrentTimestamp` 또는 `Instant.now()` 바인딩)
- 이 구현에서는 **Kotlin `Instant.now()`를 파라미터로 바인딩**하여 애플리케이션 서버 기준으로 일관성 유지
- NTP 동기화 권장 문서화
- `leaseTime`을 충분히 크게 설정하여 수 초 drift를 흡수

### 8.2 위험 2: PK 충돌 판별의 DB 벤더 의존성

**위험**: `INSERT` 시 PK 충돌 예외의 SQL error code가 H2/PG/MySQL마다 다름.

**완화**:
- H2: `23505` (UNIQUE_CONSTRAINT_VIOLATION)
- PostgreSQL: `23505` (unique_violation)
- MySQL: `1062` (ER_DUP_ENTRY)
- `ExposedSQLException` catch 후 vendor-specific code로 분기하되, **catch-all로 재시도** 처리
- 벤더 코드 판별 실패 시에도 안전하게 재시도 루프 계속 (최악의 경우 타임아웃으로 종료)

### 8.3 위험 3: 트랜잭션 격리 수준과 원자성

> **[H3 수정]** 트랜잭션 범위 및 격리 수준 명시.

**위험**: `READ COMMITTED` 격리에서 UPDATE + INSERT + SELECT 사이에 다른 트랜잭션이 동일 row를 변경할 수 있음.

**완화**:
- Exposed `transaction(db) {}` 기본 격리 수준: **`Connection.TRANSACTION_READ_COMMITTED`**
- `tryLock`의 UPDATE + INSERT + SELECT (token 검증)는 **반드시 단일 `transaction {}` 블록** 내에서 실행 (구현 의무)
- `UPDATE ... WHERE lockedUntil < now` 자체가 PostgreSQL/MySQL에서 row-level lock 획득 → phantom read 위험 최소화
- 두 트랜잭션이 동시에 같은 만료 row를 UPDATE하면 하나는 0 rows updated → 다음 루프로 진행
- `INSERT`에서 PK 충돌은 자연스러운 경합 해소 메커니즘
- token 검증 SELECT는 UPDATE/INSERT와 동일 트랜잭션이므로 dirty read 없음

### 8.4 위험 4: HikariCP Connection Pool 고갈

> **[H6 수정]** retry sleep은 반드시 `transaction {}` 바깥에서 실행.

**위험**: 많은 인스턴스가 동시에 리더 선출을 시도할 때 connection pool이 소진될 수 있음.

**완화**:
- `transaction {}` 블록이 닫히면 connection이 즉시 HikariCP 풀로 반납됨
- `Thread.sleep(retryStrategy.delayMs(attempt, remaining))` 호출은 **반드시 `transaction {}` 블록 바깥**에서 실행 (Section 3.1 시퀀스 참조)
- sleep 중에는 connection을 점유하지 않으므로 pool 고갈 위험 제거
- 트랜잭션 자체는 매우 짧음 (UPDATE + INSERT + SELECT 각 1건)
- HikariCP 설정 가이드 문서화: 최소 pool size = (동시 리더 선출 인스턴스 수 × 1.5) 권장

### 8.5 위험 5: H2 호환성 제약

**위험**: H2는 일부 SQL 구문/동작이 PostgreSQL/MySQL과 다를 수 있음.

**완화**:
- Exposed DSL만 사용하여 DB-agnostic SQL 생성 (raw SQL 금지)
- `UPDATE + INSERT` 2-step 패턴은 H2 `MODE=MYSQL` 또는 `MODE=POSTGRESQL` 없이도 표준 SQL로 동작
- 3-DB 파라미터화 테스트로 모든 DB에서 동작 검증

### 8.6 타임존 처리

> **[H5 추가]** `lockedAt`/`lockedUntil` 타임존 처리 명시.

**위험**: H2 `DATETIME`과 PostgreSQL `TIMESTAMPTZ`의 타임존 처리 방식 차이로 인한 `lockedUntil < now` 비교 오류.

**처리 방식**:
- `LeaderLockTable.lockedAt` / `lockedUntil`: Exposed `java-time` 확장 기반 `timestamp("...")` 컬럼 (UTC 저장)
- `Instant.now()` 파라미터 바인딩 → **애플리케이션 서버 기준 UTC**로 일관성 유지
- PostgreSQL: `TIMESTAMPTZ` — UTC 명시적 저장
- MySQL: `DATETIME` — JVM UTC 타임존(`TimeZone.setDefault(TimeZone.getTimeZone("UTC"))`) 설정 권장
- H2 테스트 시: `DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false` + JVM UTC 설정

**구현 의무**: 애플리케이션 서버의 JVM 타임존을 UTC로 고정하거나, JDBC URL에 `serverTimezone=UTC`를 명시합니다.
테스트 기반 클래스(`AbstractExposedJdbcLeaderTest`)에서 UTC 설정을 강제합니다.

---

## 9. 파일 구조

```
leader-exposed-jdbc/
├── build.gradle.kts                           (수정: H2/MySQL/exposed-java-time 의존성 추가)
└── src/
    ├── main/kotlin/io/bluetape4k/leader/exposed/jdbc/
    │   ├── ExposedJdbcLeaderElection.kt       — LeaderElection + AsyncLeaderElection 구현
    │   ├── ExposedJdbcLeaderGroupElection.kt  — LeaderGroupElection + AsyncLeaderGroupElection 구현
    │   ├── ExposedJdbcVirtualThreadLeaderElection.kt — VirtualThreadLeaderElection 구현
    │   ├── ExposedJdbcLeaderElectionOptions.kt — 단일 리더 옵션
    │   ├── ExposedJdbcLeaderGroupElectionOptions.kt — 그룹 리더 옵션
    │   ├── lock/
    │   │   ├── ExposedJdbcLock.kt             — 단일 락 (tryLock/unlock)
    │   │   ├── ExposedJdbcGroupLock.kt        — 그룹 슬롯 락 (tryLock/unlock)
    │   │   └── ExposedJdbcSchemaInitializer.kt — ensureSchema 유틸리티
    │   └── ExposedJdbcLeaderElectionExtensions.kt — Database 확장 함수
    └── test/
        ├── kotlin/io/bluetape4k/leader/exposed/jdbc/
        │   ├── AbstractExposedJdbcLeaderTest.kt — 3-DB 파라미터화 테스트 베이스
        │   ├── ExposedJdbcLeaderElectionTest.kt
        │   ├── ExposedJdbcLeaderGroupElectionTest.kt
        │   ├── ExposedJdbcVirtualThreadLeaderElectionTest.kt
        │   └── lock/
        │       ├── ExposedJdbcLockTest.kt
        │       └── ExposedJdbcGroupLockTest.kt
        └── resources/
            ├── junit-platform.properties      (이미 존재)
            └── logback-test.xml               (이미 존재)
```

---

## 10. 테스트 전략

### 10.1 3-DB 파라미터화 테스트

`leader-exposed-core`의 `AbstractExposedTableTest` 패턴을 따릅니다.

```kotlin
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractExposedJdbcLeaderTest {

    companion object : KLogging() {
        @JvmStatic
        fun enableDialects(): List<TestDB> = listOf(
            TestDB.H2,
            TestDB.POSTGRESQL,
            TestDB.MYSQL_V8,
        )
    }

    // TestDB → Database 변환 유틸리티
    // withDb(testDB) { ... } 또는 withTables(testDB, *tables) { ... } 사용
}
```

### 10.2 테스트 케이스 목록

#### ExposedJdbcLockTest

| 테스트 | 설명 |
|---|---|
| `tryLock - 새 lockName으로 락 획득이 성공한다` | 레코드 없는 상태에서 INSERT 성공 |
| `tryLock - 동일 lockName 중복 획득 시 대기 후 실패한다` | 이미 유효한 락 → waitTime 초과 → false |
| `tryLock - 만료된 락을 재획득할 수 있다` | UPDATE WHERE expired 성공 |
| `unlock - 토큰 일치 시 레코드가 삭제된다` | deleteWhere token=? → 1 row |
| `unlock - 토큰 불일치 시 경고 로그만 남긴다` | deleteWhere → 0 rows, 예외 없음 |
| `isHeldByCurrentInstance - 보유 중이면 true` | SELECT WHERE token=? → 존재 |
| `isHeldByCurrentInstance - 만료 후 takeover되면 false` | 다른 토큰으로 변경 → false |

#### ExposedJdbcLeaderElectionTest

| 테스트 | 설명 |
|---|---|
| `runIfLeader - 리더로 선출되어 action을 실행하고 결과를 반환한다` | 기본 성공 경로 |
| `runIfLeader - 동일 lockName에 여러 스레드 동시 접근 시 최소 1개 이상 성공한다` | MultithreadingTester |
| `runIfLeader - blank lockName은 IllegalArgumentException을 발생시킨다` | 입력 검증 |
| `runIfLeader - action 예외 발생 시 예외가 전파되고 락 레코드가 삭제된다` | finally unlock |
| `runIfLeader - 락 보유 중 짧은 waitTime으로 호출하면 null을 반환한다` | contention → null |
| `runIfLeader - leaseTime 만료 후 takeover가 성공한다` | stale lock 재획득 |
| `runAsyncIfLeader - 비동기 action 실행 후 결과를 반환한다` | CF 경로 |
| `runAsyncIfLeader - action 실패 후에도 락이 해제된다` | 오류 복구 |

#### ExposedJdbcLeaderGroupElectionTest

| 테스트 | 설명 |
|---|---|
| `runIfLeader - 그룹 슬롯을 획득하여 action을 실행한다` | 기본 성공 |
| `runIfLeader - maxLeaders개까지 동시 실행을 허용한다` | 병렬 실행 제한 검증 |
| `activeCount - 활성 슬롯 수를 정확히 반환한다` | 상태 조회 |
| `availableSlots - 가용 슬롯 수를 정확히 반환한다` | maxLeaders - activeCount |
| `runIfLeader - 모든 슬롯이 점유된 상태에서 null을 반환한다` | 슬롯 소진 |

#### ExposedJdbcVirtualThreadLeaderElectionTest

| 테스트 | 설명 |
|---|---|
| `runAsyncIfLeader - VirtualFuture로 결과를 반환한다` | await() 성공 |
| `runAsyncIfLeader - action 예외 시 VirtualFuture.await에서 전파된다` | 예외 경로 |

### 10.3 ContractTest 재사용

`leader-core`에 정의된 `AsyncLeaderElectionContractTest`의 검증 패턴을
`ExposedJdbcLeaderElection` 인스턴스로 재실행하여 인터페이스 계약 준수를 확인합니다.

---

## 11. 기술 제약

| 제약 | 설명 |
|---|---|
| Kotlin 2.3+, JVM 21 | `VirtualFuture`, `virtualFuture` 사용 |
| Exposed 1.2.0 | `org.jetbrains.exposed.v1.*` 패키지, `upsert()` DSL 가용 |
| HikariCP | Connection pool 필수 (Exposed JDBC 표준) |
| JDBC 블로킹 I/O | 코루틴 환경에서는 `withContext(Dispatchers.IO)` 필요 (이 모듈에서는 미사용, R2DBC가 담당) |
| 3-DB 호환 | H2 (in-memory), PostgreSQL (Testcontainers), MySQL 8 (Testcontainers) |
| `leader-exposed-core` 수정 금지 | 이미 구현 완료된 스키마 모듈에 파일 추가/수정 불가 |
| `!!` 금지, `@Synchronized` 금지 | bluetape4k Kotlin 코딩 규칙 |
| atomicfu 제약 | 클래스 프로퍼티 레벨만 허용, 메서드 로컬 변수 금지 |

---

## 12. 확장 함수 (Convenience API)

MongoDB 패턴과 동일하게, `Database` 확장 함수를 제공합니다:

```kotlin
// ExposedJdbcLeaderElectionExtensions.kt

fun <T> Database.runIfLeader(
    lockName: String,
    options: ExposedJdbcLeaderElectionOptions = ExposedJdbcLeaderElectionOptions.Default,
    action: () -> T,
): T? = ExposedJdbcLeaderElection(this, options).runIfLeader(lockName, action)

fun <T> Database.runAsyncIfLeader(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: ExposedJdbcLeaderElectionOptions = ExposedJdbcLeaderElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> = ExposedJdbcLeaderElection(this, options).runAsyncIfLeader(lockName, executor, action)

fun <T> Database.runIfLeaderGroup(
    lockName: String,
    options: ExposedJdbcLeaderGroupElectionOptions = ExposedJdbcLeaderGroupElectionOptions.Default,
    action: () -> T,
): T? = ExposedJdbcLeaderGroupElection(this, options).runIfLeader(lockName, action)
```

---

## 13. lockName 검증

공통 `validateLockName()` 함수를 사용합니다 (2-tier 설계).
Exposed JDBC 백엔드에서는 MongoDB의 `:slot:` 금지 규칙이 불필요하므로
공통 검증만 적용합니다.

```kotlin
internal fun validateExposedLockName(lockName: String) {
    io.bluetape4k.leader.validateLockName(lockName)
    // Exposed JDBC 전용 추가 규칙 없음 (향후 필요 시 확장)
}
```

---

## 14. DoD (Definition of Done)

### 구현 완료 조건

- [ ] `ExposedJdbcLeaderElection` 구현 (`LeaderElection` + `AsyncLeaderElection`)
- [ ] `ExposedJdbcLeaderGroupElection` 구현 (`LeaderGroupElection` + `AsyncLeaderGroupElection`)
- [ ] `ExposedJdbcVirtualThreadLeaderElection` 구현 (`VirtualThreadLeaderElection`)
- [ ] `ExposedJdbcLock` 단일 락 클래스 (`tryLock`/`unlock`/`isHeldByCurrentInstance`)
- [ ] `ExposedJdbcGroupLock` 그룹 락 클래스 (슬롯별 `tryLock`/`unlock`)
- [ ] `ExposedJdbcLeaderElectionOptions` + `ExposedJdbcLeaderGroupElectionOptions` 옵션 클래스
- [ ] `ExposedJdbcSchemaInitializer` — `ensureSchema()` 유틸리티
- [ ] `ExposedJdbcLeaderElectionExtensions` — `Database` 확장 함수
- [ ] 이력 기록 (`recordHistory: Boolean`) 선택적 지원

### 테스트 완료 조건

- [ ] 3-DB 파라미터화 테스트 (H2, PostgreSQL, MySQL_V8)
- [ ] `AbstractExposedJdbcLeaderTest` 베이스 클래스
- [ ] `ExposedJdbcLockTest` — 단일 락 단위 테스트
- [ ] `ExposedJdbcGroupLockTest` — 그룹 락 단위 테스트
- [ ] `ExposedJdbcLeaderElectionTest` — 통합 테스트
- [ ] `ExposedJdbcLeaderGroupElectionTest` — 통합 테스트
- [ ] `ExposedJdbcVirtualThreadLeaderElectionTest` — VirtualThread 테스트
- [ ] 멀티스레드 경합 테스트 (`MultithreadingTester`)
- [ ] takeover 시나리오 테스트 (lease 만료 후 재획득)

### 빌드/문서 완료 조건

- [ ] `build.gradle.kts` 의존성 추가 (H2, MySQL, exposed-java-time, bluetape4k.exposed.jdbc.tests)
- [ ] `./gradlew :leader-exposed-jdbc:build` 성공
- [ ] `./gradlew :leader-exposed-jdbc:test` 성공 (3-DB 모두 통과)
- [ ] 모든 public API에 한국어 KDoc
- [ ] README.md + README.ko.md
- [ ] `junit-platform.properties`: PER_CLASS + parallel=false

---

## 15. 구현 순서 (권장)

```
Phase 1: 기반 클래스
  1. build.gradle.kts 의존성 추가
  2. ExposedJdbcLeaderElectionOptions / ExposedJdbcLeaderGroupElectionOptions
  3. ExposedJdbcSchemaInitializer (ensureSchema)
  4. validateExposedLockName

Phase 2: 락 클래스
  5. ExposedJdbcLock (tryLock / unlock / isHeldByCurrentInstance)
  6. ExposedJdbcLockTest (3-DB)
  7. ExposedJdbcGroupLock
  8. ExposedJdbcGroupLockTest (3-DB)

Phase 3: Election 클래스
  9. ExposedJdbcLeaderElection
  10. ExposedJdbcLeaderElectionTest (3-DB)
  11. ExposedJdbcLeaderGroupElection
  12. ExposedJdbcLeaderGroupElectionTest (3-DB)
  13. ExposedJdbcVirtualThreadLeaderElection
  14. ExposedJdbcVirtualThreadLeaderElectionTest

Phase 4: 확장 + 문서
  15. ExposedJdbcLeaderElectionExtensions
  16. 이력 기록 (recordHistory) 지원
  17. README.md + README.ko.md
  18. KDoc 최종 검수
```

---

## Appendix A: MongoDB 패턴 대응표

| MongoDB | Exposed JDBC |
|---|---|
| `MongoCollection<Document>` | `Database` (Exposed) |
| `MongoLock` | `ExposedJdbcLock` |
| `MongoLock.LOCK_COLLECTION_NAME` | `LeaderLockTable` (leader-exposed-core) |
| `MongoLock.GROUP_LOCK_COLLECTION_NAME` | `LeaderGroupLockTable` (leader-exposed-core) |
| `MongoLeaderElection` | `ExposedJdbcLeaderElection` |
| `MongoLeaderGroupElection` | `ExposedJdbcLeaderGroupElection` |
| `MongoLeaderElectionOptions` | `ExposedJdbcLeaderElectionOptions` |
| `MongoLeaderGroupElectionOptions` | `ExposedJdbcLeaderGroupElectionOptions` |
| `MongoSuspendLeaderElection` | (R2DBC 모듈에서 구현) |
| `findOneAndUpdate(upsert=true)` | `UPDATE WHERE expired` + `INSERT` |
| `deleteOne(token=?)` | `deleteWhere { token eq ? }` |
| `ensureIndexes(collection)` | `ensureSchema(db)` via `SchemaUtils.createMissingTablesAndColumns` |
| `retryDelay` + AWS full jitter | `RetryStrategy.Jitter(baseDelayMs = 50L)` (기본값) |
| `token` (UUID fencing) | 동일 |

## Appendix B: SQL 예시 (Exposed DSL → 생성 SQL)

### B.1 tryLock — UPDATE (stale lock 재획득)

```kotlin
// Exposed DSL
LeaderLockTable.update(
    where = {
        (LeaderLockTable.lockName eq lockKey) and
        (LeaderLockTable.lockedUntil less now)
    }
) {
    it[token] = newToken
    it[lockOwner] = owner
    it[lockedAt] = now
    it[lockedUntil] = now.plus(leaseTime)
}
```

생성 SQL (PostgreSQL):
```sql
UPDATE bluetape4k_leader_locks
SET token = ?, lock_owner = ?, locked_at = ?, locked_until = ?
WHERE lock_name = ? AND locked_until < ?
```

### B.2 tryLock — INSERT (최초 획득)

```kotlin
// Exposed DSL
LeaderLockTable.insert {
    it[lockName] = lockKey
    it[lockOwner] = owner
    it[token] = newToken
    it[lockedAt] = now
    it[lockedUntil] = now.plus(leaseTime)
}
```

### B.3 unlock — DELETE (토큰 기반)

```kotlin
// Exposed DSL
LeaderLockTable.deleteWhere {
    (LeaderLockTable.lockName eq lockKey) and
    (LeaderLockTable.token eq currentToken)
}
```
