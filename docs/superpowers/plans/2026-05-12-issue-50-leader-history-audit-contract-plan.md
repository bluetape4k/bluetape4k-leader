# Plan — Issue #50 Leader History/Audit Common Contract

**Spec**: `docs/superpowers/specs/2026-05-12-issue-50-leader-history-audit-contract-design.md`
**Issue**: #50
**작성일**: 2026-05-13
**Scope**: `leader-core`, `leader-micrometer`, `leader-exposed-core`, `leader-exposed-jdbc`,
`leader-exposed-r2dbc`, `leader-mongodb`, `leader-redis-lettuce`, `leader-spring-boot`

---

## Plan 단계 결정 (spec deferred)

Spec 검토 라운드(특히 Round 6)에서 plan 단계 결정으로 미룬 항목을 먼저 확정한다.

| # | 항목 | 결정 | 근거 |
|---|------|------|------|
| D1 | `truncateUtf8` 위치 | **leader-core internal v1** + bluetape4k-support 승격 TODO | 단일 PR 경계 유지, cross-repo coupling 최소화 |
| D2 | `@ConsistentCopyVisibility` 적용 여부 | **적용**. `LeaderLockHistoryRecord`에 추가 | 프로젝트 선례 (`LockHandleElement.kt`) 존재 → 컨벤션 |
| D3 | `AnnotationKind` enum DB 저장 방식 | **String name 저장** (length=32 컬럼) | Exposed `enumerationByName` 사용; ordinal 의존 회피 |
| D4 | `errorType` anonymous class fallback | `qualifiedName ?: javaClass.name` | qualifiedName null 가능 — Codex P2 |
| D5 | `metadata.toMap()` 16-key 자르기 iteration order | **non-deterministic 명시 + LinkedHashMap 권고** | 비결정성을 KDoc로 노출 |
| D6 | `MicrometerSafeLeaderHistoryRecorder.sink` 접근 | **`protected val sink`로 변경** (spec §2-2 이미 반영) | subclass `sink::class.simpleName` 접근 |
| D7 | AsyncLeaderElector wiring 스켈레톤 | **IO executor 위임 (전용 또는 외부 주입)** | ForkJoinPool 블록 방지 |
| D8 | VirtualThreadLeaderElector wiring | **carrier-thread 임시 차단 허용**, sink 직접 호출 | virtual thread 자체가 IO-friendly |
| D9 | Group elector wiring (Lettuce/Redisson/Exposed/Mongo) | **v1 deferred** — interface만 호환되게 두고 구현은 issue follow-up | matrix complexity 폭증 회피 |
| D10 | JMH 마이크로벤치마크 모듈 신설 | **`leader-core` jmh sourceSet 신설** (me.champeau.jmh plugin) | 현재 repo에 JMH 사용처 없음 — buildSrc plugin 추가 필요 |

---

## Phase 0 — Prerequisites (truncateUtf8 + 사전 정비)

### T1: `String.truncateUtf8(maxBytes)` 유틸리티 추가
**complexity: medium**
**모듈**: `leader-core`
**파일**: `leader-core/src/main/kotlin/io/bluetape4k/leader/internal/StringTruncateSupport.kt` (신규)

내용:
- `internal fun String.truncateUtf8(maxBytes: Int): String` top-level 함수
- spec §2-5 알고리즘: UTF-8 byte 인코딩 → continuation byte (`0b10xxxxxx`) 경계 후퇴 → 재디코드
- `requireGe(maxBytes, 0, "maxBytes")` (bluetape4k extension)
- KDoc (English): `## Behavior / Contract` — surrogate pair 경계 안전, 그래핀 boundary는 out-of-scope
- TODO 주석: `// TODO(#50): promote to bluetape4k-support after v1 stabilizes (D1)`

### T2: `LockIdentity.AnnotationKind` 가시성/위치 확인
**complexity: low**
**모듈**: `leader-core`
**파일**: `leader-core/src/main/kotlin/io/bluetape4k/leader/LockIdentity.kt` (수정)

확인 사항:
- `AnnotationKind` enum이 `public`인지 (audit record 필드 타입으로 사용됨)
- 만약 nested이면 import path 검증 — `io.bluetape4k.leader.LockIdentity.AnnotationKind`
- 필요 시 KDoc 추가: "Reused as `LeaderLockHistoryRecord.kind` type per #50 spec"

---

## Phase 1 — leader-core Model (Status / Record / Key / Sink)

### T3: `LeaderHistoryStatus` enum 추가
**complexity: low**
**모듈**: `leader-core`
**파일**: `leader-core/src/main/kotlin/io/bluetape4k/leader/history/LeaderHistoryStatus.kt` (신규)

내용:
- `enum class LeaderHistoryStatus { ACQUIRED, COMPLETED, FAILED, EXPIRED }`
- KDoc (English): 4-state lifecycle + EXPIRED sweeper v1 out-of-scope 명시

### T4: `LeaderLockHistoryRecord` data class + factory
**complexity: high**
**모듈**: `leader-core`
**파일**: `leader-core/src/main/kotlin/io/bluetape4k/leader/history/LeaderLockHistoryRecord.kt` (신규)

내용:
- `@ConsistentCopyVisibility` annotation 적용 (D2)
- `data class LeaderLockHistoryRecord private constructor(...)` — spec §1-3 필드 전체
- `companion object : KLogging()` + `serialVersionUID = 1L`
- `MAX_ERROR_MESSAGE_BYTES = 512`, `MAX_METADATA_KEYS = 16`, `MAX_METADATA_VALUE_LENGTH = 256`
- `operator fun invoke(...)` factory — `requireNotBlank("lockName")`, `requireNotBlank("token")`, `metadata.toMap()` 방어 복사
- `Serializable` 구현
- KDoc (English): `## Behavior / Contract` — invariants, factory 검증, copy() 가시성 정책

### T5: `LeaderHistoryKey` data class
**complexity: low**
**모듈**: `leader-core`
**파일**: `leader-core/src/main/kotlin/io/bluetape4k/leader/history/LeaderHistoryKey.kt` (신규)

내용:
- `data class LeaderHistoryKey(id: Long?, historyId: String?, lockName: String, token: String, slotId: String? = null) : Serializable`
- `init { lockName.requireNotBlank("lockName"); token.requireNotBlank("token") }` — 빈 자연 키 조합 방지 (H-medium: 필드 검증)
- `companion object : KLogging()` + `serialVersionUID`
- Update 전략 결정 helper 미포함 (sink adapter 책임)
- KDoc (English): spec §1-4 update 전략 표 그대로 옮김

### T6: `LeaderHistorySink` / `SuspendLeaderHistorySink` SPI interface
**complexity: high**
**모듈**: `leader-core`
**파일**:
- `leader-core/src/main/kotlin/io/bluetape4k/leader/history/LeaderHistorySink.kt` (신규)
- `leader-core/src/main/kotlin/io/bluetape4k/leader/history/SuspendLeaderHistorySink.kt` (신규)

내용:
- spec §2 두 interface 시그니처 그대로
- `recordFailed(key, finishedAt, durationMs, errorType: String?, errorMessage: String?)` — `Throwable` 아님
- KDoc (English):
  - thread-safety 계약
  - `recordAcquired` null 반환 시 caller 동작 (recordCompleted/recordFailed skip 또는 fallback key)
  - `CancellationException` 미전달 정책
  - SuspendSink: blocking IO를 `runInterruptible {}` 안에서 호출하는 것은 **MUST** — coroutine cancel 시 interrupt flag 복원 보장 (H6: SHOULD→MUST 격상)
  - KDoc에 반드시 코드 예제 포함 (Round 2 User H2):
    ```kotlin
    // MUST — JDBC/synchronous blocking call
    override suspend fun recordAcquired(...): LeaderHistoryKey? = runInterruptible {
        transaction(database) { LeaderLockHistoryTable.insert { ... } get id }
    }
    // NOT needed — MongoDB Reactive Streams is natively async
    override suspend fun recordAcquired(...): LeaderHistoryKey? {
        collection.insertOne(document).awaitSingle()
    }
    ```
  - null-key fallback 계약 명시: `recordAcquired`가 null 반환 시 caller는 `(lockName, token)`로 `LeaderHistoryKey` 생성 후 `recordCompleted/recordFailed` 호출 — 구현체는 null id 인자를 gracefully 처리해야 함

### T7: `NoopLeaderHistorySink` / `NoopSuspendLeaderHistorySink`
**complexity: low**
**모듈**: `leader-core`
**파일**: `leader-core/src/main/kotlin/io/bluetape4k/leader/history/NoopLeaderHistorySink.kt` (신규, 두 object 동일 파일)

내용:
- `object NoopLeaderHistorySink : LeaderHistorySink` — 모든 메서드 no-op, recordAcquired = null
- `object NoopSuspendLeaderHistorySink : SuspendLeaderHistorySink` — 동일
- KDoc (English): 기본 fallback sink, audit 미사용 시 명시적 주입 가능

### T8: `effectiveStatus()` extension
**complexity: low**
**모듈**: `leader-core`
**파일**: `leader-core/src/main/kotlin/io/bluetape4k/leader/history/LeaderHistoryStatusExtensions.kt` (신규)

내용:
- `fun LeaderLockHistoryRecord.effectiveStatus(now: Instant = Instant.now()): LeaderHistoryStatus`
- spec §3 본문 그대로 — ACQUIRED + lockedUntil < now → EXPIRED
- KDoc (English): clock skew 주의 (앱/DB now 차이), parametric `now` 노출 이유

---

## Phase 2 — leader-core Recorder (open class + shared sanitize)

### T9: shared internal `sanitize()` / `sanitizeForLog()` top-level fun
**complexity: high**
**모듈**: `leader-core`
**파일**: `leader-core/src/main/kotlin/io/bluetape4k/leader/history/LeaderHistoryRecorderSupport.kt` (신규)

내용:
- `internal fun sanitize(record: LeaderLockHistoryRecord): LeaderLockHistoryRecord`
  - `errorMessage`: `truncateUtf8(MAX_ERROR_MESSAGE_BYTES)` 적용
  - `metadata`: `entries.take(MAX_METADATA_KEYS)` 후 key는 `take(64).sanitizeForLog()`, value는 `take(MAX_METADATA_VALUE_LENGTH).sanitizeForLog()` (H5: value도 제어문자 치환 필수)
  - 비결정성 노트: iteration order (D5)
- `internal fun String.sanitizeForLog(): String = replace(Regex("[\\p{Cntrl}\\u2028\\u2029]"), "?")`
  - acceptance criteria pattern 그대로
- `SafeLeaderHistoryRecorder` / `SuspendSafeLeaderHistoryRecorder`에서 공통 참조 (private 불가)

### T10: `SafeLeaderHistoryRecorder` open class
**complexity: high**
**모듈**: `leader-core`
**파일**: `leader-core/src/main/kotlin/io/bluetape4k/leader/history/SafeLeaderHistoryRecorder.kt` (신규)

내용:
- `open class SafeLeaderHistoryRecorder(protected val sink: LeaderHistorySink)`
- `companion object : KLogging()`
- 3개 open fun (`recordAcquired`, `recordCompleted`, `recordFailed`) — spec §2-2 catch ladder:
  1. `catch (e: CancellationException) { throw e }`
  2. `catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }`
  3. `catch (e: Exception) { log.warn(...) }`
- `recordFailed(error: Throwable?)`:
  - `error is CancellationException` → warn + skip (IAE 미전파)
  - `errorType = error?.let { it::class.qualifiedName ?: it.javaClass.name }` (D4)
  - `errorMessage = error?.message?.sanitizeForLog()?.truncateUtf8(MAX_ERROR_MESSAGE_BYTES)` (Round 2 Dev MEDIUM: errorMessage도 제어문자 sanitize 필수)
- `sink` 호출 전 `sanitize(record)` 적용 (`recordAcquired`만)
- KDoc (English): audit-isolation 계약, Error 전파 정책 (JVM-fatal 허용)

### T11: `SuspendSafeLeaderHistoryRecorder` open class
**complexity: high**
**모듈**: `leader-core`
**파일**: `leader-core/src/main/kotlin/io/bluetape4k/leader/history/SuspendSafeLeaderHistoryRecorder.kt` (신규)

내용:
- T10과 동일 구조의 suspend 버전 — spec §2-3
- 3개 open suspend fun
- IE catch 주석: "best-effort, sink 구현은 `runInterruptible {}` 권고"
- `sanitize()` / `sanitizeForLog()` shared internal fun 참조 (T9 의존)
- KDoc (English)

---

## Phase 3 — leader-micrometer (Counter + Naming)

### T12: `MicrometerNames`에 history counter 이름 추가
**complexity: low**
**모듈**: `leader-micrometer`
**파일**: `leader-micrometer/src/main/kotlin/.../MicrometerNames.kt` (수정)

내용:
- `const val HISTORY_SINK_FAILURES = "leader.history.sink.failures"`
- `const val HISTORY_ACQUIRE_MISSING = "leader.history.acquire.missing"`
- `const val HISTORY_MONGODB_INDEX_STATE = "leader.history.mongodb.index.state"` (T19에서 사용)
- `const val HISTORY_MONGODB_TTL_DISABLED = "leader.history.mongodb.ttl.disabled"` (T19에서 사용)

### T12-S: `MicrometerNames`에 suspend counter 이름 추가 (Round 2 Dev H1 대응)
**complexity: low**
**모듈**: `leader-micrometer`

`T12`에 이미 정의된 상수 외 추가 불필요 — 동일 상수(`HISTORY_SINK_FAILURES`, `HISTORY_ACQUIRE_MISSING`)를 suspend decorator에서도 재사용. 별도 파일 생성 없음.

---

### T13: `MicrometerSafeLeaderHistoryRecorder` 구현
**complexity: high**
**모듈**: `leader-micrometer`
**파일**: `leader-micrometer/src/main/kotlin/.../history/MicrometerSafeLeaderHistoryRecorder.kt` (신규)

내용 (H1 수정):
- `class MicrometerSafeLeaderHistoryRecorder(sink: LeaderHistorySink, private val meterRegistry: MeterRegistry) : SafeLeaderHistoryRecorder(CounterAwareSinkDecorator(sink, meterRegistry, sink::class.simpleName ?: "unknown"))`
- **method override 없음** — Counter 측정은 전적으로 `CounterAwareSinkDecorator`(T14)가 담당
- 생성자에서 `CounterAwareSinkDecorator`로 래핑된 sink를 `super(...)`에 전달
- `companion object : KLogging()`
- KDoc (English): "Counter instrumentation is applied via `CounterAwareSinkDecorator` injected at construction time; no method overrides are needed."

### T14: T13 보조 — `CounterAwareSinkDecorator` (internal)
**complexity: medium**
**모듈**: `leader-micrometer`
**파일**: `leader-micrometer/src/main/kotlin/.../history/internal/CounterAwareSinkDecorator.kt` (신규, internal)

내용 (H1 + CE arm 추가):
- T13의 wrapping 전략용 internal decorator
- `internal class CounterAwareSinkDecorator(delegate: LeaderHistorySink, registry: MeterRegistry, sinkSimpleName: String) : LeaderHistorySink`
- 각 메서드 catch ladder (H1 — CE arm 필수):
  ```kotlin
  try {
      val result = delegate.recordXxx(...)
      // recordAcquired only: if (result == null) HISTORY_ACQUIRE_MISSING.increment(...)
      return result
  } catch (e: CancellationException) { throw e }  // H1: CE rethrow BEFORE counter increment
  catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
  catch (e: Exception) { HISTORY_SINK_FAILURES.increment(...); throw e }
  ```
- `recordAcquired` null 반환 시 `HISTORY_ACQUIRE_MISSING.increment(...)` — **정상 반환 분기, catch 아님**
- KDoc (English) — CE/IE rethrow 정책 + counter 증가 시점 명시

### T13-S: `CounterAwareSuspendSinkDecorator` + `MicrometerSuspendSafeLeaderHistoryRecorder` (Round 2 Dev H1)
**complexity: high**
**모듈**: `leader-micrometer`
**파일**:
- `leader-micrometer/src/main/kotlin/.../history/internal/CounterAwareSuspendSinkDecorator.kt` (신규, internal)
- `leader-micrometer/src/main/kotlin/.../history/MicrometerSuspendSafeLeaderHistoryRecorder.kt` (신규)

내용:
- `internal class CounterAwareSuspendSinkDecorator(delegate: SuspendLeaderHistorySink, registry: MeterRegistry, sinkSimpleName: String) : SuspendLeaderHistorySink`
  - T14와 동일한 catch ladder (CE rethrow → IE rethrow → counter increment): suspend 버전
  - `recordAcquired` null → `HISTORY_ACQUIRE_MISSING.increment(...)`
- `open class MicrometerSuspendSafeLeaderHistoryRecorder(sink: SuspendLeaderHistorySink, registry: MeterRegistry) : SuspendSafeLeaderHistoryRecorder(CounterAwareSuspendSinkDecorator(sink, registry, sink::class.simpleName ?: "unknown"))`
  - method override 없음 — T13과 동일 패턴
- **이유**: R2DBC + MongoDB 백엔드는 `SuspendLeaderHistorySink`를 주입받으므로 blocking `MicrometerSafeLeaderHistoryRecorder`가 적용되지 않음. 누락 시 suspend backend 모니터링 전체 blind spot.
- T36-A 테스트에 suspend counterpart 케이스 추가
- KDoc (English)

---

## Phase 4 — leader-exposed-core (Model migration + Schema)

### T15: `HistoryStatus` → `LeaderHistoryStatus` typealias 이전
**complexity: medium**
**모듈**: `leader-exposed-core`
**파일**:
- `leader-exposed-core/src/main/kotlin/io/bluetape4k/leader/exposed/tables/HistoryStatus.kt` (수정)
- `leader-exposed-jdbc`/`leader-exposed-r2dbc` 사용처 (선택적으로 import 변경)

내용:
- `HistoryStatus.kt`를 다음으로 교체:
  ```kotlin
  @Deprecated(
      "Use io.bluetape4k.leader.history.LeaderHistoryStatus.",
      ReplaceWith("io.bluetape4k.leader.history.LeaderHistoryStatus"),
      DeprecationLevel.WARNING,
  )
  typealias HistoryStatus = LeaderHistoryStatus
  ```
- 모든 `HistoryStatus.ACQUIRED.name` 호출은 typealias로 그대로 동작 — sed/refactor 불필요
- 기존 테스트 회귀 0 검증

### T16: `LeaderLockHistoryTable` schema 확장
**complexity: high**
**모듈**: `leader-exposed-core`
**파일**: `leader-exposed-core/src/main/kotlin/io/bluetape4k/leader/exposed/tables/LeaderLockHistoryTable.kt` (수정)

내용:
- 신규 컬럼 (모두 nullable, default 없음 — spec §4-2):
  - `errorType: varchar(255).nullable()`
  - `errorMessage: varchar(512).nullable()`
  - `kind: varchar(32).nullable()`  (D3 — `AnnotationKind.name`)
  - `participantId: varchar(255).nullable()`
  - `metadata: text().nullable()`  (JSON serialized; serializer 선택은 T17)
  - `slotId: varchar(255).nullable()` — **신규 추가** (H4: Redisson permitId가 UUID 문자열이므로 Int 변환 불가 → VARCHAR로 직접 저장)
- 기존 `slot` (Int) 컬럼: 유지 (additive-only). jdbc/r2dbc sink에서 `slotId.toIntOrNull()`이 non-null이면 기존 `slot`에도 함께 저장 (역방향 호환); Redisson UUID는 `slot`에 null 저장.
- KDoc 영문 갱신

### T17: Flyway DDL 스크립트 (PostgreSQL + MySQL 8.0+)
**complexity: medium**
**모듈**: `leader-exposed-core`
**파일**:
- `leader-exposed-core/src/main/resources/db/migration/V202605130001__add_history_audit_columns.sql` (PostgreSQL)
- `leader-exposed-core/src/main/resources/db/migration/mysql/V202605130001__add_history_audit_columns.sql` (MySQL 8.0+)
- `leader-exposed-core/src/main/resources/db/migration/V202605130002__rollback_history_audit_columns.sql` (rollback, 별도)

내용 (PostgreSQL):
```sql
SET lock_timeout = '3s';
ALTER TABLE leader_lock_history ADD COLUMN IF NOT EXISTS error_type    VARCHAR(255);
ALTER TABLE leader_lock_history ADD COLUMN IF NOT EXISTS error_message VARCHAR(512);
ALTER TABLE leader_lock_history ADD COLUMN IF NOT EXISTS kind          VARCHAR(32);
ALTER TABLE leader_lock_history ADD COLUMN IF NOT EXISTS participant_id VARCHAR(255);
ALTER TABLE leader_lock_history ADD COLUMN IF NOT EXISTS metadata      TEXT;
ALTER TABLE leader_lock_history ADD COLUMN IF NOT EXISTS slot_id      VARCHAR(255);  -- H4
```

내용 (MySQL 8.0+): `ALGORITHM=INSTANT` 사용 — spec §4-2 정확히 그대로 + `slot_id VARCHAR(255)` 추가
Rollback: **DB별 별도 파일** (H-medium: MySQL/PG rollback DDL 분리)
- PostgreSQL rollback: `V202605130002__rollback_history_audit_columns.sql`
- MySQL rollback: `mysql/V202605130002__rollback_history_audit_columns.sql`
- 각 파일: `ALTER TABLE ... DROP COLUMN ...` × 6 (slot_id 포함)
- 멀티 포드 운영 환경 가이드 (README/KDoc): Flyway/Liquibase 사용, `SchemaUtils.createMissingTablesAndColumns`는 개발 전용

### T18: metadata JSON serializer 결정 + 유틸
**complexity: medium**
**모듈**: `leader-exposed-core`
**파일**: `leader-exposed-core/src/main/kotlin/io/bluetape4k/leader/exposed/history/MetadataJsonCodec.kt` (신규)

내용:
- bluetape4k 표준 JSON util 사용 — Jackson `objectMapper.writeValueAsString(map)` / `readValue(...)`
- `internal object MetadataJsonCodec { fun encode(map: Map<String,String>): String?; fun decode(json: String?): Map<String,String> }`
- empty map → null 저장 (DB 공간 절약)

---

## Phase 5 — leader-exposed-jdbc + leader-exposed-r2dbc

### T19: `ExposedLeaderHistorySink` (JDBC, blocking)
**complexity: high**
**모듈**: `leader-exposed-jdbc`
**파일**: `leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/history/ExposedLeaderHistorySink.kt` (신규)

내용:
- `class ExposedLeaderHistorySink(private val database: Database) : LeaderHistorySink`
- `recordAcquired(record)`:
  - `transaction(database) { LeaderLockHistoryTable.insert { ... } get LeaderLockHistoryTable.id }`
  - 반환된 Long id → `LeaderHistoryKey(id = ..., lockName = ..., token = ...)`
  - 예외 시 caller(SafeLeaderHistoryRecorder)가 catch — sink는 던지기만 함
- `recordCompleted` / `recordFailed`:
  - `id != null` → `UPDATE WHERE id = ? AND token = ?` (전략 1)
  - else `historyId != null` → 전략 2
  - else → `UPDATE WHERE lockName = ? AND token = ?` (전략 3, null-key fallback)
  - 업데이트된 row 수 0이면 warn log (no-op, 이중 write 방지)
- `kind`: `AnnotationKind.name` 저장 (D3)
- `slotId` 저장 (H4): `LeaderLockHistoryTable.slotId` VARCHAR 컬럼에 `record.slotId` 직접 저장; 기존 `slot` INT 컬럼은 `record.slotId?.toIntOrNull()` (순수 숫자형 token 역방향 호환용) — `toInt()` NFE 완전 제거
- **`override fun deleteOlderThan(cutoff: Instant, limit: Int): Int`** 구현 (Round 2 Dev H2: 반드시 override):
  ```kotlin
  override fun deleteOlderThan(cutoff: Instant, limit: Int): Int =
      transaction(database) {
          LeaderLockHistoryTable.deleteWhere(limit = limit) {
              LeaderLockHistoryTable.startedAt less cutoff
          }
      }
  ```
- KDoc (English)
- **보안 위협 모델 주석 (Round 2 Security medium)**: `token` 컬럼은 live lock release 권한이 있는 credential임. DB read access는 lock backend와 동일 trust boundary로 제한해야 함 — KDoc에 `@apiNote` 명시

### T20: `ExposedJdbcLeaderElector` refactor — 인라인 history 제거 + recorder 주입
**complexity: high**
**모듈**: `leader-exposed-jdbc`
**파일**: `leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/ExposedJdbcLeaderElector.kt` (수정)

내용:
- 기존 `recordAcquired()` (line ~247) / `recordFinished(...)` (line ~263) 인라인 history 코드 제거
- constructor에 optional `historyRecorder: SafeLeaderHistoryRecorder? = null` 추가
- `runIfLeader` 본문을 spec §2-1 wiring 예제 구조로 재작성:
  - `tryAcquire` → null 시 즉시 null 반환
  - `try { record + recordAcquired + effectiveKey + try-catch(action) + recordCompleted/recordFailed } finally { try { backend.unlock() } catch (Exception) log.warn }`
  - CE catch arm: throw e
  - IE catch arm: `Thread.currentThread().interrupt(); throw e`
- backwards-compat: 기존 `recordHistory: Boolean` option은 deprecation 노트 추가 — recorder null 주입과 동일 의미

### T20-BC: Breaking Change 표기 + CHANGELOG + 기존 테스트 갱신
**complexity: medium**
**모듈**: `leader-exposed-jdbc`, `leader-exposed-r2dbc`
**파일**:
- `CHANGELOG.md` (수정)
- `leader-exposed-jdbc/src/test/kotlin/.../*Test*.kt` 영향 받는 기존 테스트 파일들 (수정)
- `leader-exposed-r2dbc/src/test/kotlin/.../*Test*.kt` 영향 받는 기존 테스트 파일들 (수정)

내용 (H2):
- 기존 `ExposedJdbcLeaderElector` / `ExposedR2dbcLeaderElector` 는 action에서 던져진 예외를 **rethrow**했으나, spec 새 wiring 후 **null 반환**으로 변경됨
- `CHANGELOG.md`에 `### BREAKING CHANGES` 항목 추가:
  ```
  - `ExposedJdbcLeaderElector.runIfLeader()` now returns `null` instead of rethrowing
    action exceptions. Callers that relied on exception propagation must migrate.
  - `ExposedR2dbcLeaderElector.runIfLeader()` — same change.
  ```
- 기존 테스트 중 action-exception rethrow를 `assertFailsWith`로 검증하는 케이스 → `shouldBe null` 검증으로 수정
- KDoc `@throws` 제거 + `@return null when not elected OR when action throws` 갱신

### T21: `ExposedJdbcLeaderGroupElector` refactor (v1: recorder param only)
**complexity: medium**
**모듈**: `leader-exposed-jdbc`
**파일**: `leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/ExposedJdbcLeaderGroupElector.kt` (수정)

내용:
- constructor에 optional `historyRecorder: SafeLeaderHistoryRecorder? = null` 추가 (인터페이스 호환)
- **wiring 본문 구현은 v1 deferred** (D9). 기존 동작 그대로 유지하되 recorder가 주입되면 best-effort로 single 슬롯 record 1건 시도 (interface 호환만 검증)
- KDoc에 "Group elector wiring follow-up: tracked in #50 v2" 명시

### T22: `ExposedSuspendLeaderHistorySink` (R2DBC, natively suspend)
**complexity: high**
**모듈**: `leader-exposed-r2dbc`
**파일**: `leader-exposed-r2dbc/src/main/kotlin/io/bluetape4k/leader/exposed/r2dbc/history/ExposedSuspendLeaderHistorySink.kt` (신규)

내용:
- `class ExposedSuspendLeaderHistorySink(private val database: R2dbcDatabase) : SuspendLeaderHistorySink`
- `suspendTransaction(database) { ... }` 사용 — bluetape4k-exposed-r2dbc 표준
- **`runInterruptible {}` 미사용** (R2DBC는 natively non-blocking)
- 그 외 로직은 T19와 동일
- **`override suspend fun deleteOlderThan(cutoff: Instant, limit: Int): Int`** 구현 (Round 2 Dev H2):
  ```kotlin
  override suspend fun deleteOlderThan(cutoff: Instant, limit: Int): Int =
      suspendTransaction(database) {
          LeaderLockHistoryTable.deleteWhere(limit = limit) {
              LeaderLockHistoryTable.startedAt less cutoff
          }
      }
  ```

### T23: `ExposedR2dbcLeaderElector` refactor
**complexity: high**
**모듈**: `leader-exposed-r2dbc`
**파일**: `leader-exposed-r2dbc/src/main/kotlin/io/bluetape4k/leader/exposed/r2dbc/ExposedR2dbcLeaderElector.kt` (수정)

내용:
- T20과 동일한 패턴, suspend 버전
- constructor `historyRecorder: SuspendSafeLeaderHistoryRecorder? = null`
- `try { ... } finally { try { backend.unlock() } catch (CancellationException) { throw e } catch (Exception) { log.warn } }` — **`runCatching` 금지** (suspend + CE 삼킴)

### T24: `ExposedR2dbcLeaderGroupElector` recorder param
**complexity: medium**
**모듈**: `leader-exposed-r2dbc`
**파일**: `leader-exposed-r2dbc/src/main/kotlin/io/bluetape4k/leader/exposed/r2dbc/ExposedR2dbcLeaderGroupElector.kt` (수정)

T21와 동일 정책 (interface 호환만, 본문 wiring v1 deferred)

---

## Phase 6 — leader-mongodb

### T25: `MongoLeaderHistorySink` 구현
**complexity: high**
**모듈**: `leader-mongodb`
**파일**: `leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/history/MongoLeaderHistorySink.kt` (신규)

내용:
- `class MongoLeaderHistorySink(...) : SuspendLeaderHistorySink` — MongoDB Reactive Streams 드라이버 사용 → suspend 친화
- collection: `bluetape4k_leader_history` (옵션으로 override 가능, default 상수)
- spec §5 schema 그대로 document 생성
- `recordAcquired` → `_id`(ObjectId) 또는 `historyId`(UUID) 발급 → `LeaderHistoryKey(historyId = uuid, ...)` 반환
- `recordCompleted` / `recordFailed`:
  - `historyId` 우선, fallback `lockName + token`으로 `updateOne`
  - `findOneAndUpdate` 또는 `updateOne` 사용 (returnDocument 불필요)
- `kind` 저장: `AnnotationKind.name` String (D3)
- **`override suspend fun deleteOlderThan(cutoff: Instant, limit: Int): Int`** 구현 (Round 2 Dev H2):
  ```kotlin
  override suspend fun deleteOlderThan(cutoff: Instant, limit: Int): Int {
      val result = collection.deleteMany(lt("startedAt", cutoff)).awaitSingle()
      // Note: MongoDB driver does not support LIMIT on deleteMany. Use scheduled sweeper TTL instead.
      return result.deletedCount.toInt()
  }
  ```
  - MongoDB는 `deleteMany` LIMIT 미지원 → TTL index가 primary retention 메커니즘; `deleteOlderThan`은 보조 (즉시 정리 용도). KDoc에 TTL index 우선 사용 명시

### T26: MongoDB index 생성 (lazy background coroutine)
**complexity: high**
**모듈**: `leader-mongodb`
**파일**: `leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/history/MongoLeaderHistoryIndexer.kt` (신규)

내용 (H-medium: CoroutineScope lifecycle 추가):
- `class MongoLeaderHistoryIndexer(database, config, registry: MeterRegistry?) : Closeable`
- startup 시 별도 `CoroutineScope(SupervisorJob + Dispatchers.IO)` 로 index 빌드 (startup blocking 회피)
- **`override fun close()`**: scope 취소 후 indexBuildJob 완료 대기 with timeout (Round 2 Dev H4 / Ops H3)
  ```kotlin
  override fun close() {
      runCatching { scope.cancel() }
      runCatching {
          runBlocking { withTimeoutOrNull(5_000L) { indexBuildJob?.join() } }
              ?: log.warn { "MongoLeaderHistoryIndexer: shutdown timed out after 5s" }
      }
  }
  ```
  - timeout 5초 기본값; `bluetape4k.leader.mongodb.history.indexer.shutdownTimeoutMs` 프로퍼티로 조정 가능
  - Spring `@PreDestroy` / `DisposableBean` 연동 가능
- index 생성:
  - `{ lockName: 1, startedAt: -1 }`
  - `{ token: 1 }`
  - TTL: `{ startedAt: 1 }` with `expireAfterSeconds = ttlDays * 86400` (ttlDays > 0일 때만)
- 빌드 실패 시 지수 백오프 (1s → 2s → 4s) 최대 3회 재시도 후 ERROR log
- gauge state 관리:
  - `-1` 빌드 실패, `0` 빌드 중, `1` 준비 완료
  - `leader.history.mongodb.index.state` (T12 상수 사용)
- KDoc (English): `close()` lifecycle contract 포함

### T27: TTL gauge live supplier
**complexity: medium**
**모듈**: `leader-mongodb`
**파일**: `leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/history/MongoHistoryTtlGauge.kt` (신규)

내용:
- `class MongoHistoryTtlGauge(config: MongoHistoryConfig, registry: MeterRegistry)`
- `Gauge.builder(HISTORY_MONGODB_TTL_DISABLED) { if (config.ttlDays <= 0) 1.0 else 0.0 }.register(registry)`
- live supplier — runtime config 변경 즉시 반영
- startup WARN log when `ttlDays <= 0`

### T28: `MongoHistoryConfig` 옵션 클래스
**complexity: low**
**모듈**: `leader-mongodb`
**파일**: `leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/history/MongoHistoryConfig.kt` (신규)

내용:
- `data class MongoHistoryConfig(val collectionName: String = DEFAULT, val ttlDays: Long = 90)`
- prefix: `bluetape4k.leader.mongodb.history.*`

### T29: Mongo electors recorder param
**complexity: medium**
**모듈**: `leader-mongodb`
**파일**:
- `leader-mongodb/src/main/kotlin/.../MongoLeaderElector.kt` (수정)
- `leader-mongodb/src/main/kotlin/.../MongoSuspendLeaderElector.kt` (수정)
- `leader-mongodb/src/main/kotlin/.../MongoLeaderGroupElector.kt` (수정 — interface 호환만)
- `leader-mongodb/src/main/kotlin/.../MongoSuspendLeaderGroupElector.kt` (수정 — interface 호환만)

내용:
- blocking elector → optional `SafeLeaderHistoryRecorder?` 추가
- suspend elector → optional `SuspendSafeLeaderHistoryRecorder?` 추가
- T20/T23 wiring 패턴 적용 (single elector만; group은 v1 deferred — D9)

---

## Phase 7 — leader-redis-lettuce (Token entropy upgrade)

### T30: Lettuce token Base58 8자 → 22자 업그레이드
**complexity: high**
**모듈**: `leader-redis-lettuce`
**파일**:
- `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/semaphore/LettuceSlotTokenGroup.kt` (수정)
- `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/LettuceLeaderElector.kt` 등 token 발급처 (수정)

내용 (H7: SecureRandom backing 명시 추가):
- `Base58.randomString(8)` → `Base58.randomString(22)` (또는 UUID v4 기반 함수)
- **SecureRandom 사용 확인 필수**: `Base58.randomString()` 내부가 `SecureRandom`을 backing RNG로 사용하는지 검증. `Random` 또는 `ThreadLocalRandom` 사용 시 SecureRandom으로 교체.
  - 검증 방법: `Base58` 구현체의 RNG 인스턴스 확인 (`instanceof SecureRandom`)
  - 코드 주석: `// Token generation uses SecureRandom for ≥128-bit entropy (see #50 spec §1-3)`
- 신규 acquire부터 ≥128 bit 엔트로피 보장
- 기존 8자 token 레코드 호환: `recordCompleted/Failed`는 자연 키 `(lockName, token)` 그대로 매칭 — DB migration 불필요
- legacy compat 정책 코드 주석 명시 (spec §1-3 그대로)
- README/KDoc 갱신은 T39

### T31: Lettuce electors recorder param (best-effort wiring)
**complexity: medium**
**모듈**: `leader-redis-lettuce`
**파일**:
- `leader-redis-lettuce/src/main/kotlin/.../LettuceLeaderElector.kt` (수정)
- `leader-redis-lettuce/src/main/kotlin/.../LettuceSuspendLeaderElector.kt` (수정)

내용:
- optional recorder param 추가 (interface 호환만; Redis는 v1 best-effort, sink 외부 주입 시 동작)
- 본문 wiring 패턴은 spec §2-1 그대로 적용 (single elector)
- Group elector는 D9에 따라 v1 deferred (param 추가 X 또는 추가하되 미사용)

---

## Phase 8 — leader-spring-boot (Auto-configuration)

### T32: `LeaderHistoryAutoConfiguration` 신규
**complexity: high**
**모듈**: `leader-spring-boot`
**파일**:
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/history/LeaderHistoryAutoConfiguration.kt` (신규)
- `leader-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (등록 추가)

내용 (Round 2 Ops H2 — 순서 수정):
- `@AutoConfiguration(before = [LeaderElectionAutoConfiguration::class], after = [MetricsAutoConfiguration::class])` (Round 2 Ops H2: recorder bean이 elector 생성 이전에 반드시 준비되어야 함)
- `@ConditionalOnProperty(prefix = "bluetape4k.leader.history", name = ["enabled"], matchIfMissing = true)`
- beans:
  - `@Bean @ConditionalOnMissingBean fun leaderHistorySink(): LeaderHistorySink = NoopLeaderHistorySink` + **INFO log**: "Leader history audit disabled (NoopLeaderHistorySink). Provide a LeaderHistorySink bean to enable."
  - `@Bean @ConditionalOnMissingBean @ConditionalOnBean(LeaderHistorySink::class) fun safeLeaderHistoryRecorder(sink): SafeLeaderHistoryRecorder` — **단**, MeterRegistry 존재 시 Micrometer 변형 우선
  - `@ConditionalOnBean(SuspendLeaderHistorySink::class) fun suspendSafeLeaderHistoryRecorder(ObjectProvider<SuspendLeaderHistorySink>)` — `ifUnique { }` (T34)
- `@ConditionalOnClass(name = ["io.micrometer.core.instrument.MeterRegistry"])` 가드된 inner config:
  - `MicrometerSafeLeaderHistoryRecorder` (blocking) + `MicrometerSuspendSafeLeaderHistoryRecorder` (suspend, T13-S) bean 우선 등록
- Multi-sink WARN: `ObjectProvider.ifAvailable` 에서 2건 이상 시 WARN "Multiple [Suspend]LeaderHistorySink beans found; use @Primary to disambiguate."
- `AutoConfiguration.imports`에 `LeaderHistoryAutoConfiguration` 등록 순서가 `LeaderElectionAutoConfiguration` 이전임을 주석으로 명시
- KDoc (English) — 적용 순서 명시

### T33: `LeaderElectionAutoConfiguration`에 recorder 주입
**complexity: medium**
**모듈**: `leader-spring-boot`
**파일**: `leader-spring-boot/src/main/kotlin/.../LeaderElectionAutoConfiguration.kt` (수정)

내용:
- 모든 backend별 elector bean 정의에 `historyRecorder: SafeLeaderHistoryRecorder?`(또는 suspend 변종) 주입
- `@Autowired(required = false)` 또는 `ObjectProvider<SafeLeaderHistoryRecorder>` 사용
- recorder 부재 시 elector는 그대로 동작 (audit off)

### T34: SuspendSink 자동 등록 (R2DBC / Mongo)
**complexity: medium**
**모듈**: `leader-spring-boot`
**파일**: 별도 inner config or `LeaderHistoryAutoConfiguration` 내부

내용 (H-medium: NoUniqueBeanDefinitionException 방지):
- `@ConditionalOnClass` 가드:
  - R2dbcDatabase 존재 → `ExposedSuspendLeaderHistorySink` 후보
  - MongoDB 존재 → `MongoLeaderHistorySink` 후보
- **`ObjectProvider<SuspendLeaderHistorySink>` 사용**: `@Autowired(required = false)` 대신 `ObjectProvider.ifUnique { }` 패턴으로 다중 bean 충돌(`NoUniqueBeanDefinitionException`) 방지
  - `objectProvider.ifUnique { sink -> SuspendSafeLeaderHistoryRecorder(sink) }` — `ifUnique` 는 1건일 때만 실행; 0건 / 2건 이상이면 null
  - 0건: recorder 미생성 (audit off), 2건 이상: WARN log + 사용자가 `@Primary` 명시 요구
- 명시적 sink bean이 있으면 그대로 사용

### T-Retention: Retention 배치 삭제 작업 (`@Scheduled`)
**complexity: medium**
**모듈**: `leader-spring-boot`
**파일**: `leader-spring-boot/src/main/kotlin/.../history/LeaderHistoryRetentionJob.kt` (신규) + `LeaderAopAutoConfiguration.kt` (수정)

내용 (H3: spec §4-2 retention job — 계획에서 누락):
- `@Component class LeaderHistoryRetentionJob(private val sink: LeaderHistorySink)`
  - `@Scheduled(cron = "\${bluetape4k.leader.history.retention.cron:0 0 2 * * ?}")` (기본 매일 새벽 2시)
  - 30일 기본 retention: `bluetape4k.leader.history.retention.days` (default 30)
  - 1000-row 청크 반복 삭제: `while (deleted >= chunkSize) { deleted = sink.deleteOlderThan(cutoff, chunkSize) }`
- `LeaderHistorySink`에 `fun deleteOlderThan(cutoff: Instant, limit: Int = 1000): Int` default method 추가 (noop 기본 구현, returns 0)
- `SuspendLeaderHistorySink`에 대응하는 `suspend fun deleteOlderThan(...)` default method 추가
- **멀티 pod 동시 실행 방지 (Round 2 Ops H1)**: 메서드에 `@LeaderElection("bluetape4k-leader-history-retention")` 추가 (이 라이브러리 자체 AOP 활용 — dogfooding). `@ConditionalOnBean(LeaderElector::class)` 으로 guard. 단순 단일 pod 배포에서 `@LeaderElection` 없이 사용 시 WARN log.
- **Noop 감지 WARN (Round 2 Ops medium)**: `@PostConstruct`에서 주입된 sink가 `NoopLeaderHistorySink` 인스턴스이면 WARN: "Retention is enabled but sink is Noop — no rows will be deleted."
- **wall-clock 예산 (Round 2 Ops medium)**: loop에 `maxDurationMs` 가드 추가 (기본 5분):
  ```kotlin
  val deadline = System.currentTimeMillis() + maxDurationMs
  var deleted: Int
  do {
      deleted = sink.deleteOlderThan(cutoff, chunkSize)
      deletedCounter.increment(deleted.toDouble())
  } while (deleted >= chunkSize && System.currentTimeMillis() < deadline)
  if (System.currentTimeMillis() >= deadline) log.warn { "Retention loop exceeded budget; remaining rows deferred." }
  ```
- `leader.history.retention.deleted` counter (Micrometer, T12) 추가
- auto-config 조건: `@ConditionalOnProperty(name = ["bluetape4k.leader.history.retention.enabled"], matchIfMissing = true)`
- KDoc (English): retention period, chunk size, cron, maxDurationMs, @LeaderElection guard 설정 예시 포함

### T-Retention-S: `SuspendLeaderHistoryRetentionJob` (Round 2 Dev H3)
**complexity: medium**
**모듈**: `leader-spring-boot`
**파일**: `leader-spring-boot/src/main/kotlin/.../history/SuspendLeaderHistoryRetentionJob.kt` (신규)

내용:
- `@Component class SuspendLeaderHistoryRetentionJob(private val sink: SuspendLeaderHistorySink, private val scope: CoroutineScope)` — R2DBC / MongoDB 전용
- `@Scheduled` + `@LeaderElection` 동일 구조
- 내부: `scope.launch { while (deleted >= chunkSize && withinBudget) { deleted = sink.deleteOlderThan(cutoff, chunkSize) } }`
- auto-config: `@ConditionalOnBean(SuspendLeaderHistorySink::class)` + 별도 property 불필요 (T-Retention의 `retention.enabled` 공유)
- **주의**: `LeaderHistoryRetentionJob`(blocking)과 `SuspendLeaderHistoryRetentionJob`(suspend)가 동시 등록되지 않도록 `@ConditionalOnMissingBean(LeaderHistoryRetentionJob::class)` 추가 (JDBC sink + R2DBC sink 동시 존재 시 blocking 우선)

---

## Phase 9 — Tests

### T35-A: leader-core 단위 테스트 — Record / Key / Status / Sink
**complexity: medium**
**모듈**: `leader-core`
**파일**: `leader-core/src/test/kotlin/io/bluetape4k/leader/history/LeaderHistoryModelTest.kt` (신규)

테스트 케이스 (spec §7-1 매핑):
- `LeaderLockHistoryRecord()` factory blank `lockName` → `assertFailsWith<IllegalArgumentException>`
- factory blank `token` → 동일
- factory metadata 방어 복사 — 원본 `MutableMap` 변경 후 record 영향 없음
- `LeaderHistoryKey` update 전략 우선순위 (id != null > historyId != null > lockName+token) 시뮬레이션
- `LeaderHistoryStatus` 4-state 검증
- `effectiveStatus()` ACQUIRED + `lockedUntil < now` → EXPIRED
- `effectiveStatus()` 그 외 status → 원본 그대로
- `NoopLeaderHistorySink.recordAcquired` → null
- `NoopSuspendLeaderHistorySink.recordAcquired` → null (`runTest`)

도구: JUnit 5, MockK, Kluent

### T35-B: leader-core 단위 테스트 — `truncateUtf8` + `sanitizeForLog` + `sanitize`
**complexity: medium**
**모듈**: `leader-core`
**파일**: `leader-core/src/test/kotlin/io/bluetape4k/leader/internal/StringTruncateSupportTest.kt` (신규)
+ `leader-core/src/test/kotlin/io/bluetape4k/leader/history/LeaderHistoryRecorderSupportTest.kt` (신규)

테스트 케이스:
- `truncateUtf8` ASCII 경계
- `truncateUtf8` 한글(UTF-8 3바이트) 경계 — 멀티바이트 boundary safety (절단된 문자 미포함)
- `truncateUtf8` 이모지(surrogate pair) 경계
- `truncateUtf8(maxBytes=0)` → 빈 문자열
- `truncateUtf8(maxBytes < 0)` → `IllegalArgumentException` (requireGe)
- `sanitizeForLog`: `\n` / `\r` / `\t` → `?`
- `sanitizeForLog`: U+2028 / U+2029 → `?` (acceptance criteria)
- `sanitizeForLog`: 일반 문자는 그대로
- `sanitize(record)` errorMessage 512B 초과 → truncate
- `sanitize(record)` metadata 17개 → 16개로 줄어듦 (iteration order 비결정성은 도메인 검증 X — count만 확인)
- `sanitize(record)` metadata key에 `\n` 포함 → `?`로 대체
- `sanitize(record)` metadata value 256자 초과 → truncate

### T35-C: leader-core 단위 테스트 — `SafeLeaderHistoryRecorder` (blocking) CE/IE rethrow + sink 흡수
**complexity: high**
**모듈**: `leader-core`
**파일**: `leader-core/src/test/kotlin/io/bluetape4k/leader/history/SafeLeaderHistoryRecorderTest.kt` (신규)

테스트 케이스 (spec §7-1, 6행 IE rethrow 포함):
- `recordAcquired` sink throws RuntimeException → warn log + null 반환
- `recordAcquired` sink throws CE → `assertFailsWith<CancellationException>`
- `recordAcquired` sink throws IE → `assertFailsWith<InterruptedException>` + `Thread.currentThread().isInterrupted == true` (비파괴 read)
- `recordCompleted` sink throws RuntimeException → warn log, 정상 반환
- `recordCompleted` sink throws CE → rethrow
- `recordCompleted` sink throws IE → rethrow + interrupt flag 복원
- `recordFailed(error = null)` → sink call with `errorType=null, errorMessage=null`
- `recordFailed(error = ...)` errorType 파생 (`qualifiedName` or javaClass.name fallback)
- `recordFailed(error = CE)` → skip + warn log, sink 미호출, IAE 미전파
- `recordFailed` sink throws IE → rethrow + interrupt flag 복원
- `recordFailed` sink throws CE → rethrow
- `recordFailed` error.message 512B 초과 → truncate 후 sink 호출
- `recordAcquired` sink throws Error(`OutOfMemoryError`) → **전파** (audit-isolation 미적용)
- **stack trace 누출 방지 (H-medium)**: warn log 메시지에 `e.stackTraceToString()` 포함 여부 확인 → 포함 시 실패 (log 검증용 `TestAppender` 사용)

### T35-D: leader-core 단위 테스트 — `SuspendSafeLeaderHistoryRecorder`
**complexity: high**
**모듈**: `leader-core`
**파일**: `leader-core/src/test/kotlin/io/bluetape4k/leader/history/SuspendSafeLeaderHistoryRecorderTest.kt` (신규)

테스트 케이스: T35-C의 suspend 버전 (모두 `runTest`)
- 6개 메서드 × IE rethrow + interrupt flag 복원
- CE rethrow는 `coInvoking { } shouldThrow CancellationException::class`
- **IE interrupt flag 누출 시연 테스트 (H6)**: `runInterruptible {}` 없이 blocking IO를 직접 호출하는 mock sink에서 IE 발생 → `Thread.currentThread().isInterrupted == true` 확인. "MUST use `runInterruptible`" KDoc 계약 검증.

### T35-E: leader-core 동시성 테스트 — `SafeLeaderHistoryRecorder`
**complexity: high**
**모듈**: `leader-core`
**파일**: `leader-core/src/test/kotlin/io/bluetape4k/leader/history/SafeLeaderHistoryRecorderConcurrencyTest.kt` (신규)

테스트 케이스:
- blocking: `MultithreadingTester(workers=8, rounds=50)` — `recordAcquired` 동시 호출 → counter 정합 + race 없음 (in-memory sink 사용)
- suspend: `SuspendedJobTester` — 동일 패턴
- 직접 `Thread`/`Executors`/`coroutineScope.launch` 금지 (CLAUDE.md memory)

### T36-A: leader-micrometer 단위 테스트
**complexity: medium**
**모듈**: `leader-micrometer`
**파일**: `leader-micrometer/src/test/kotlin/.../history/MicrometerSafeLeaderHistoryRecorderTest.kt` (신규)

테스트 케이스 (spec §7-1):
- `recordAcquired` 정상 반환 → counter 미증가
- `recordAcquired` sink throws Exception → `leader.history.sink.failures{operation=recordAcquired, sink_type=...}` increment 1
- `recordAcquired` sink returns null → `leader.history.acquire.missing{lock_name=..., sink_type=...}` increment 1
- `recordCompleted` sink throws Exception → `leader.history.sink.failures{operation=recordCompleted, ...}` increment 1
- `recordFailed` sink throws Exception → `leader.history.sink.failures{operation=recordFailed, ...}` increment 1
- counter tag 값 검증 (`lock_name`, `sink_type`)

도구: `SimpleMeterRegistry`, MockK

### T37-A: leader-exposed-jdbc 통합 테스트 (Testcontainers)
**complexity: high**
**모듈**: `leader-exposed-jdbc`
**파일**: `leader-exposed-jdbc/src/test/kotlin/io/bluetape4k/leader/exposed/jdbc/history/ExposedLeaderHistorySinkTest.kt` (신규)

인프라: PostgreSQL + MySQL 8.0+ (둘 다, Testcontainers `XxxServer.Launcher.xxx` 싱글톤 — CLAUDE.md memory)

테스트 케이스:
- ACQUIRED 레코드 insert → `LeaderHistoryKey(id != null)` 반환
- ACQUIRED → COMPLETED `id` 기반 update
- ACQUIRED → FAILED `id` 기반 update + errorType/errorMessage 컬럼 저장
- key.id=null + historyId=null fallback → `(lockName, token)` update 성공
- update target row 0 → no-op + warn log (이중 write 방지)
- metadata 1-항목 JSON serialize/deserialize round-trip
- `kind = SINGLE` / `GROUP` 저장값 확인 (D3)
- **effectiveKey 단위 테스트 (H-medium)**: `recordAcquired` 가 null 반환 시 wiring 코드에서 `effectiveKey` fallback 생성 → `recordCompleted/recordFailed` 호출 성공 — key null 시 history 전체 skip되지 않음 검증

도구: `@TestInstance(PER_CLASS)`, `bluetape4k-junit5`, `bluetape4k-testcontainers`

### T37-B: leader-exposed-r2dbc 통합 테스트
**complexity: high**
**모듈**: `leader-exposed-r2dbc`
**파일**: `leader-exposed-r2dbc/src/test/kotlin/.../history/ExposedSuspendLeaderHistorySinkTest.kt` (신규)

T37-A와 동일 시나리오의 `runTest` 버전.
- PostgreSQL R2DBC + MySQL R2DBC (8.0+)
- `runInterruptible` 사용 X (R2DBC native suspend)

### T37-C: leader-mongodb 통합 테스트
**complexity: high**
**모듈**: `leader-mongodb`
**파일**: `leader-mongodb/src/test/kotlin/io/bluetape4k/leader/mongodb/history/MongoLeaderHistorySinkTest.kt` (신규)

인프라: `MongoDBServer.Launcher.mongodb` 싱글톤

테스트 케이스:
- ACQUIRED document insert → `LeaderHistoryKey(historyId != null)` 반환
- historyId 기반 update (COMPLETED / FAILED)
- TTL index 적용 확인 (`getIndexes()` 응답에 `expireAfterSeconds` 존재)
- `MongoLeaderHistoryIndexer` 정상 빌드 후 gauge state == 1.0
- TTL == 0 → gauge `leader.history.mongodb.ttl.disabled` == 1.0

### T38: DDL migration 테스트 (PostgreSQL + MySQL 8.0+)
**complexity: high**
**모듈**: `leader-exposed-core`
**파일**: `leader-exposed-core/src/test/kotlin/io/bluetape4k/leader/exposed/migration/HistoryAuditDdlMigrationTest.kt` (신규)

인프라: PostgreSQL 12+ + MySQL 8.0+ Testcontainers 싱글톤

테스트 케이스 (spec §7-5):
- 기존 leader_lock_history 테이블 + 데이터 → `V202605130001` 적용 → 신규 컬럼 nullable 확인 + 기존 레코드 손상 0
- MySQL `ALGORITHM=INSTANT` 검증 (`SHOW CREATE TABLE` 또는 information_schema)
- PostgreSQL `lock_timeout = '3s'` 적용 확인
- Rollback `V202605130002` 적용 → 컬럼 제거 + 기존 레코드 유지
- 멀티 포드 시뮬레이션 X (운영 검증 항목이므로 docs로만 가이드)

### T39: Lettuce token entropy 회귀 테스트
**complexity: medium**
**모듈**: `leader-redis-lettuce`
**파일**: `leader-redis-lettuce/src/test/kotlin/.../LettuceTokenEntropyTest.kt` (신규)

테스트 케이스 (H7: SecureRandom backing 검증 추가):
- 신규 token length ≥ 22자 (Base58) 또는 UUID 포맷 검증
- **SecureRandom backing assertion**: `Base58` RNG 인스턴스가 `SecureRandom`의 인스턴스임을 reflection으로 검증 (`assertThat(rng).isInstanceOf(SecureRandom::class.java)`)
- 1000회 발급 → 충돌 0 (확률적 검증)
- 8자 legacy token으로 기록된 history 레코드의 `recordCompleted` 자연 키 매칭 성공

### T40: JMH 마이크로벤치마크 (`leader-core` jmh sourceSet)
**complexity: high**
**모듈**: `leader-core`
**파일**:
- `buildSrc/src/main/kotlin/.../JmhPluginConvention.kt` (신규 — me.champeau.jmh 적용)
- `buildSrc/build.gradle.kts` (수정 — JMH plugin dependency 추가, Round 2 Dev MEDIUM)
- `leader-core/src/jmh/kotlin/io/bluetape4k/leader/history/HistoryRecorderBenchmark.kt` (신규)
- `leader-core/build.gradle.kts` (jmh 플러그인 + kover exclusion + benchmark sourceSet)

내용 (Round 2 Dev MEDIUM: buildSrc 의존성 + gradlePlugin 등록 명시):
- `buildSrc/build.gradle.kts` 추가:
  ```kotlin
  dependencies {
      implementation("me.champeau.jmh:me.champeau.jmh.gradle.plugin:<version>") // 버전은 Libs catalog 참조
  }
  ```
- `buildSrc/build.gradle.kts` gradlePlugin 등록:
  ```kotlin
  gradlePlugin { plugins { create("jmhConventions") {
      id = "bluetape4k.jmh-conventions"
      implementationClass = "JmhConventionPlugin"
  } } }
  ```
- `JmhConventionPlugin : Plugin<Project>` — `me.champeau.jmh` apply + `jmh { jmhVersion.set("1.37") }` 기본 설정
- benchmark scenarios:
  - baseline: `runIfLeader` without sink
  - with `NoopLeaderHistorySink`
  - with in-memory `LeaderHistorySink`
- 측정 metric: throughput + average time
- `kover.exclude` 또는 sourceSet 제외 — kover memory rule (CLAUDE.md)
- spec §2-6 성능 계약 검증: p99 overhead ≤ 1 ms (in-memory)

---

## Phase 10 — Documentation

### T41: leader-core KDoc + README
**complexity: medium**
**모듈**: `leader-core`
**파일**:
- 모든 신규 public 타입 KDoc (English, `## Behavior / Contract` section)
- `leader-core/README.md` (수정)
- `leader-core/README.ko.md` (수정)

내용:
- README architecture section에 history/audit SPI 추가 (Mermaid diagram)
- Sink 작성 가이드 (when to use blocking vs suspend, runInterruptible 권고)
- `effectiveStatus()` 사용 예시

### T42: leader-exposed-core README
**complexity: medium**
**모듈**: `leader-exposed-core`
**파일**: `leader-exposed-core/README.md` + `README.ko.md`

내용:
- DDL migration 가이드 (PostgreSQL + MySQL 8.0+, Flyway 경로)
- retentionDays 설정 가이드 (30일 default)
- `HistoryStatus` deprecation 노트

### T43: leader-mongodb README
**complexity: medium**
**모듈**: `leader-mongodb`
**파일**: `leader-mongodb/README.md` + `README.ko.md`

내용:
- history collection + TTL 설정 (90일 default)
- index 빌드 정책 (lazy background)
- index/TTL gauge 모니터링 가이드

### T44: leader-micrometer README
**complexity: medium**
**모듈**: `leader-micrometer`
**파일**: `leader-micrometer/README.md` + `README.ko.md`

내용:
- 신규 counter / gauge 4종 (sink.failures, acquire.missing, mongodb.index.state, mongodb.ttl.disabled) 문서화
- tag 의미, 운영 대시보드 가이드

### T45: CLAUDE.md (root) 갱신
**complexity: low**
**모듈**: repo root
**파일**: `CLAUDE.md`

내용:
- "History/Audit 계약" 섹션 추가 — sink SPI, recorder open class, 4-state lifecycle
- elector constructor optional `historyRecorder` 파라미터 패턴 명시

### T46: lessons doc
**complexity: low**
**모듈**: docs
**파일**: `docs/lessons/2026-05-12-leader-history-audit.md`

내용:
- spec 라운드 6회 critic 결과 요약 (P1 수렴, IE rethrow catch arm 등)
- audit-isolation vs JVM-fatal Error 정책
- `runInterruptible` IE policy
- `@ConsistentCopyVisibility` 적용 사례

---

## Phase 11 — Verification

### T46-Spring: Spring Boot auto-config 통합 smoke test (Round 2 Ops MEDIUM)
**complexity: medium**
**모듈**: `leader-spring-boot`
**파일**: `leader-spring-boot/src/test/kotlin/.../history/LeaderHistoryAutoConfigurationIntegrationTest.kt` (신규)

내용:
- `@SpringBootTest(classes = [TestApplication::class])` 최소 컨텍스트
- 검증 시나리오:
  1. 실제 sink 미등록 → `NoopLeaderHistorySink` bean 존재 + recorder 미생성 + INFO log 발생
  2. `ExposedLeaderHistorySink` bean 등록 → `SafeLeaderHistoryRecorder` bean 자동 생성
  3. Micrometer `@ConditionalOnClass` 활성 시 `MicrometerSafeLeaderHistoryRecorder` 우선 등록
  4. R2DBC + MongoDB sink 동시 등록 → `ObjectProvider.ifUnique` 결과 null + WARN log
  5. `LeaderElectionAutoConfiguration` elector bean이 recorder bean 주입받아 정상 동작
  6. `LeaderHistoryRetentionJob` bean 등록 확인 (retention.enabled=true)
- Spring Boot 컨텍스트 로드 순서 검증: `LeaderHistoryAutoConfiguration` → `LeaderElectionAutoConfiguration` 순서 확인

### T47: 모듈별 빌드 + 테스트
**complexity: medium**

```bash
./gradlew :leader-core:test --no-daemon
./gradlew :leader-micrometer:test
./gradlew :leader-exposed-core:test
./gradlew :leader-exposed-jdbc:test
./gradlew :leader-exposed-r2dbc:test
./gradlew :leader-mongodb:test
./gradlew :leader-redis-lettuce:test
./gradlew :leader-spring-boot:test
./gradlew build -x test --no-daemon
./gradlew detekt
./gradlew :leader-core:jmh   # spec §2-6 p99 overhead 검증
```

성공 기준:
- 기존 테스트 회귀 0
- IDE diagnostics zero error / no unresolved deprecation
- kover coverage ≥ 80% (production sources)
- JMH 결과: in-memory sink p99 ≤ 1 ms

### T48: PR 체크리스트 + 본문 작성
**complexity: low**

CLAUDE.md "Before Creating A PR" 체크리스트 적용:
- [ ] Module tests passed + pass count + elapsed
- [ ] `code-reviewer` skill 적용, HIGH/CRITICAL 해결
- [ ] PR title/body English
- [ ] README (all locales) updated
- [ ] English KDoc complete
- [ ] worktree 사용 (`.worktrees/feat-issue-50-leader-history-audit-contract/`)

---

## 의존 그래프

```
Phase 0 (T1: truncateUtf8, T2: AnnotationKind)
   │
   ▼
Phase 1 (T3: Status, T4: Record, T5: Key, T6: Sink, T7: Noop, T8: effectiveStatus)
   │
   ▼
Phase 2 (T9: shared sanitize → T10: SafeRecorder + T11: SuspendSafeRecorder)
   │
   ├──────────────────────────────────────┐
   ▼                                      ▼
Phase 3 (T12: Names → T13/T14: Micrometer recorder)
   │
   ▼                                      
Phase 4 (T15: typealias, T16: schema, T17: DDL, T18: JSON codec)
   │
   ├─────────────────────┬────────────────┐
   ▼                     ▼                ▼
Phase 5                Phase 6          Phase 7
(T19/T20/T21          (T25/T26/        (T30: token,
 T22/T23/T24)          T27/T28/T29)     T31: electors)
   │                     │                │
   └──────┬──────────────┴────────────────┘
          ▼
Phase 8 (T32/T33/T34: auto-config, T-Retention: retention job)
          │
          ▼
Phase 9 (T35-A..E unit/concurrency,
         T36-A micrometer,
         T37-A/B/C integration,
         T38 DDL migration,
         T39 token entropy,
         T40 JMH)
          │
          ▼
Phase 10 (T41-T46 docs)
          │
          ▼
Phase 11 (T47 verify, T48 PR)
```

**병렬 가능 구간**:
- Phase 5 / 6 / 7 — backend별 독립 (T19-T24 vs T25-T29 vs T30-T31)
- Phase 10 README/KDoc 작업 (T41-T44) — backend별 병렬

---

## Task DoD

| ID | 설명 | complexity | 모듈 | 의존 |
|----|------|------------|------|------|
| T1 | `truncateUtf8` 유틸리티 (leader-core internal) | medium | leader-core | - |
| T2 | `AnnotationKind` 가시성/위치 확인 | low | leader-core | - |
| T3 | `LeaderHistoryStatus` enum | low | leader-core | - |
| T4 | `LeaderLockHistoryRecord` data class + factory + `@ConsistentCopyVisibility` | high | leader-core | T2 |
| T5 | `LeaderHistoryKey` data class | low | leader-core | - |
| T6 | `LeaderHistorySink` / `SuspendLeaderHistorySink` SPI | high | leader-core | T4, T5 |
| T7 | `NoopLeaderHistorySink` / `NoopSuspendLeaderHistorySink` | low | leader-core | T6 |
| T8 | `effectiveStatus()` extension | low | leader-core | T3, T4 |
| T9 | shared `sanitize()` / `sanitizeForLog()` top-level internal fun | high | leader-core | T1, T4 |
| T10 | `SafeLeaderHistoryRecorder` open class | high | leader-core | T6, T9 |
| T11 | `SuspendSafeLeaderHistoryRecorder` open class | high | leader-core | T6, T9 |
| T12 | `MicrometerNames` history counter 상수 | low | leader-micrometer | - |
| T13 | `MicrometerSafeLeaderHistoryRecorder` (blocking decorator) | high | leader-micrometer | T10, T12, T14 |
| T13-S | `MicrometerSuspendSafeLeaderHistoryRecorder` + `CounterAwareSuspendSinkDecorator` | high | leader-micrometer | T11, T12 |
| T14 | `CounterAwareSinkDecorator` (internal, blocking) | medium | leader-micrometer | T6, T12 |
| T15 | `HistoryStatus` → `LeaderHistoryStatus` typealias | medium | leader-exposed-core | T3 |
| T16 | `LeaderLockHistoryTable` schema 확장 | high | leader-exposed-core | T15 |
| T17 | Flyway DDL 스크립트 (PostgreSQL + MySQL 8.0+) | medium | leader-exposed-core | T16 |
| T18 | metadata JSON codec | medium | leader-exposed-core | - |
| T19 | `ExposedLeaderHistorySink` (JDBC blocking) | high | leader-exposed-jdbc | T6, T16, T18 |
| T20 | `ExposedJdbcLeaderElector` refactor + recorder 주입 | high | leader-exposed-jdbc | T10, T19 |
| T20-BC | Breaking change 표기 + CHANGELOG + 기존 테스트 갱신 | medium | leader-exposed-jdbc, r2dbc | T20, T23 |
| T21 | `ExposedJdbcLeaderGroupElector` recorder param (v1 deferred wiring) | medium | leader-exposed-jdbc | T10 |
| T22 | `ExposedSuspendLeaderHistorySink` (R2DBC) | high | leader-exposed-r2dbc | T6, T16, T18 |
| T23 | `ExposedR2dbcLeaderElector` refactor | high | leader-exposed-r2dbc | T11, T22 |
| T24 | `ExposedR2dbcLeaderGroupElector` recorder param | medium | leader-exposed-r2dbc | T11 |
| T25 | `MongoLeaderHistorySink` (suspend) | high | leader-mongodb | T6 |
| T26 | `MongoLeaderHistoryIndexer` (lazy background + gauge) | high | leader-mongodb | T12, T28 |
| T27 | TTL gauge live supplier | medium | leader-mongodb | T12, T28 |
| T28 | `MongoHistoryConfig` | low | leader-mongodb | - |
| T29 | Mongo electors recorder param | medium | leader-mongodb | T10, T11 |
| T30 | Lettuce token Base58 8자 → 22자 업그레이드 | high | leader-redis-lettuce | - |
| T31 | Lettuce electors recorder param | medium | leader-redis-lettuce | T10, T11, T30 |
| T32 | `LeaderHistoryAutoConfiguration` | high | leader-spring-boot | T10, T11, T13 |
| T33 | `LeaderElectionAutoConfiguration` recorder 주입 | medium | leader-spring-boot | T20, T23, T29, T31 |
| T34 | SuspendSink 자동 등록 (R2DBC / Mongo, ObjectProvider 방식) | medium | leader-spring-boot | T22, T25, T32 |
| T-Retention | Retention 배치 삭제 (`@Scheduled` + `@LeaderElection`, 30일/1000-row/5분 예산) | medium | leader-spring-boot | T6, T32 |
| T-Retention-S | `SuspendLeaderHistoryRetentionJob` (R2DBC/Mongo 전용) | medium | leader-spring-boot | T6, T-Retention |
| T35-A | 단위 테스트 — Record/Key/Status/Sink | medium | leader-core | T4-T8 |
| T35-B | 단위 테스트 — truncateUtf8 / sanitize / sanitizeForLog | medium | leader-core | T1, T9 |
| T35-C | 단위 테스트 — `SafeLeaderHistoryRecorder` CE/IE/Error | high | leader-core | T10 |
| T35-D | 단위 테스트 — `SuspendSafeLeaderHistoryRecorder` | high | leader-core | T11 |
| T35-E | 동시성 테스트 — recorder (MultithreadingTester + SuspendedJobTester) | high | leader-core | T10, T11 |
| T36-A | 단위 테스트 — `MicrometerSafeLeaderHistoryRecorder` (counter 검증) | medium | leader-micrometer | T13, T14 |
| T37-A | 통합 테스트 — JDBC sink (PostgreSQL + MySQL 8.0+ Testcontainers) | high | leader-exposed-jdbc | T19, T20 |
| T37-B | 통합 테스트 — R2DBC sink | high | leader-exposed-r2dbc | T22, T23 |
| T37-C | 통합 테스트 — Mongo sink + indexer + TTL gauge | high | leader-mongodb | T25, T26, T27 |
| T38 | DDL migration 테스트 (PostgreSQL + MySQL 8.0+) | high | leader-exposed-core | T17 |
| T39 | Lettuce token entropy 회귀 테스트 | medium | leader-redis-lettuce | T30 |
| T40 | JMH 마이크로벤치마크 (sourceSet 신설 + buildSrc) | high | leader-core (+ buildSrc) | T10 |
| T41 | leader-core KDoc + README | medium | leader-core | T1-T11 |
| T42 | leader-exposed-core README | medium | leader-exposed-core | T15-T18 |
| T43 | leader-mongodb README | medium | leader-mongodb | T25-T28 |
| T44 | leader-micrometer README | medium | leader-micrometer | T13 |
| T45 | CLAUDE.md (root) 갱신 | low | repo | T10, T11 |
| T46 | lessons doc | low | docs | (all) |
| T46-Spring | Spring Boot auto-config 통합 smoke test | medium | leader-spring-boot | T32, T33, T34, T-Retention |
| T47 | 모듈별 빌드 + 테스트 + JMH 검증 | medium | all | (all impl + tests) |
| T48 | PR 체크리스트 + 본문 작성 | low | repo | T47 |

**Total**: 62 tasks (Rev 3: +5 신규 — T12-S placeholder, T13-S, T-Retention-S, T46-Spring + 기존 수정)
- high: 25 (+2: T13-S)
- medium: 27 (+3: T-Retention-S, T46-Spring + 기존 수정)
- low: 10

> **Plan Rev 3** (2026-05-14): Step 3-R Round 2 HIGH findings 7건 반영
> - Round 2 H1: T13-S `MicrometerSuspendSafeLeaderHistoryRecorder` + `CounterAwareSuspendSinkDecorator` 신규
> - Round 2 H2: T19/T22/T25 `deleteOlderThan()` 구현 명시
> - Round 2 H3: T-Retention-S `SuspendLeaderHistoryRetentionJob` 신규
> - Round 2 H4: T26 close() `withTimeoutOrNull(5_000)` 추가
> - Round 2 H5 (Ops H1): T-Retention `@LeaderElection` guard + Noop WARN + wall-clock budget
> - Round 2 H6 (Ops H2): T32 `before = [LeaderElectionAutoConfiguration]` 순서 수정
> - Round 2 H7 (User H2): T6 `runInterruptible` KDoc 예제 + null-key 계약 명시
> - MEDIUM: T10 recordFailed errorMessage sanitizeForLog, T40 buildSrc JMH deps, T46-Spring smoke test
>
> **Plan Rev 2** (2026-05-14): Step 3-R Round 1 HIGH findings H1~H7 + MEDIUM findings 반영
> - H1: T13 decorator-only 방식 명확화; T14 CE rethrow arm 추가
> - H2: T20-BC 신규 — breaking change + CHANGELOG + 테스트 갱신
> - H3: T-Retention 신규 — retention @Scheduled 배치 삭제
> - H4: T16/T17/T19 — `slot_id VARCHAR(255)` 신규 컬럼 + toInt() NFE 제거
> - H5: T9 — metadata value도 `sanitizeForLog()` 적용
> - H6: T6 `runInterruptible` SHOULD→MUST; T35-D IE leak 시연 테스트
> - H7: T30 SecureRandom backing 명시; T39 SecureRandom assertion 추가
> - Medium: T5 requireNotBlank, T26 Closeable lifecycle, T34 ObjectProvider, T35-C stack trace 누출 테스트, T37-A effectiveKey 단위 테스트, T17 rollback DDL 파일 분리

---

## 변경 영향 요약 (public API surface)

신규 public 타입 (leader-core):
- `LeaderHistoryStatus` (enum)
- `LeaderLockHistoryRecord` (data class + factory)
- `LeaderHistoryKey` (data class)
- `LeaderHistorySink` / `SuspendLeaderHistorySink` (interface)
- `NoopLeaderHistorySink` / `NoopSuspendLeaderHistorySink` (object)
- `SafeLeaderHistoryRecorder` / `SuspendSafeLeaderHistoryRecorder` (open class)
- `effectiveStatus()` (extension)

신규 public 타입 (leader-micrometer):
- `MicrometerSafeLeaderHistoryRecorder`
- `MicrometerNames`에 4개 상수 추가

신규 public 타입 (backend별):
- `ExposedLeaderHistorySink` (jdbc)
- `ExposedSuspendLeaderHistorySink` (r2dbc)
- `MongoLeaderHistorySink` (mongodb)
- `MongoLeaderHistoryIndexer`, `MongoHistoryConfig`, `MongoHistoryTtlGauge` (mongodb)

수정 public API (모든 elector constructor):
- optional `historyRecorder: SafeLeaderHistoryRecorder?` 또는 `SuspendSafeLeaderHistoryRecorder?` 파라미터 추가 — default null로 backwards-compatible

Schema migration:
- `leader_lock_history` 테이블에 5개 nullable 컬럼 (additive-only)
- 신규 MongoDB collection `bluetape4k_leader_history`

비호환 변경:
- 없음 (모두 additive — backwards-compatible)
- `leader-exposed-core.HistoryStatus`는 `typealias`로 유지 + deprecation WARNING
- Lettuce token 길이 변경 (8자 → 22자): legacy 레코드 자연 키 매칭으로 호환 — 운영 영향 없음
