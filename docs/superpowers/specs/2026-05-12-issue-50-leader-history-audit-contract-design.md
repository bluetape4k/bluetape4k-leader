# Issue #50 leader history/audit common contract design

> Issue: #50 | Type: A Full Design, spec-only first | Date: 2026-05-12
> Rev: 2 (2026-05-13) — 2-R Round 1 P1×15 반영

## 배경

`leader-exposed-core`에는 이미 `LeaderLockHistoryTable`과 `HistoryStatus`가 있고,
`leader-exposed-jdbc`/`leader-exposed-r2dbc`는 `recordHistory = true`일 때
`ACQUIRED -> COMPLETED | FAILED` 이력을 best-effort로 기록한다.

하지만 이 모델은 Exposed 모듈 내부의 부가 기능으로만 존재한다. `leader-core`에는
이력 레코드, 상태 전환, 저장 실패 정책을 설명하는 공통 계약이 없고, MongoDB,
Redis, Hazelcast, ZooKeeper가 audit 기능을 추가할 때 각자 다른 필드와 상태 의미를
invent할 위험이 있다.

Issue #72는 `@LeaderGroupElection`의 `leaderId` 지원을 위해 group elector API와
소유권 검증을 확장하는 작업이다. #50과 #72 사이에 직접 선후관계는 없다. 다만 #50의
필드 명명은 #72의 `leaderId`와 충돌하지 않도록 `lockOwner`, `token`, `slotId`,
`participantId`의 의미를 분리해야 한다.

## 현재 근거

- #50 본문은 `leader-core` 수준의 `LeaderLockHistoryRecord`, `HistoryStatus`,
  `LeaderHistorySink` 또는 `LeaderElectionAuditStore` 계약을 요구한다.
- #50 댓글 기준으로 Exposed schema의 핵심 부족분이었던 `token`, `slot`,
  `lockedUntil`은 이미 `LeaderLockHistoryTable`에 반영되어 있다.
- `LeaderLockHistoryTable` 현재 필드:
  `id`, `lockName`, `lockOwner`, `token`, `slot`, `lockedUntil`, `status`,
  `startedAt`, `finishedAt`, `durationMs`.
- Exposed JDBC/R2DBC는 `recordAcquired()`가 history id를 반환하고,
  완료/실패 전환은 `(id, token)`으로 대상 레코드를 식별한다.
- Exposed 구현은 audit 저장 실패를 warn log 후 무시한다. 리더 선출 결과나 action
  결과는 audit 저장 성공 여부에 의존하지 않는다.
- Exposed lesson은 `CancellationException`과 `CompletableFuture.cancel()`을
  `FAILED`로 기록하지 않도록 정리했다.
- MongoDB는 lock collection만 있고 history collection이 없다. lock document는
  `expireAt` TTL index와 per-instance token으로 소유권을 추적한다.
- Redis/Hazelcast는 TTL 기반 volatile primitive가 중심이며, audit은 기본 리더
  선출 동작과 분리해야 한다.
- **PR #209 (feat/leader-group-leaderid-72)**: Redisson group elector에
  `auditLeaderId` → `RMap` 기록을 추가했다. 이 map은 in-flight live 상태 추적용
  단기 TTL 구조이며, 이번 spec에서 설계하는 영속 audit log와 **계층 분리**된 역할을
  가진다. 둘은 v1에서 공존하며, auditMap은 live 상태용, sink는 완료 기록용으로
  역할이 다르다.

## 문제

1. 공통 history 상태와 필드 의미가 없어 저장소별 audit 구현이 서로 달라질 수 있다.
2. Exposed의 `HistoryStatus`가 `leader-exposed-core`에 있어 MongoDB 등 다른 모듈이
   재사용할 수 없다.
3. group slot 식별자가 저장소마다 다르다. Exposed는 integer slot, Mongo/Hazelcast는
   numeric string, Lettuce/Redisson은 token/permit id를 `slotId`로 사용한다.
4. `lockOwner`는 현재 Exposed 옵션의 node/owner 식별자이고, #72의 `leaderId`와
   같은 개념이 아니다.
5. audit 저장 실패가 리더 선출에 영향을 주지 않아야 하지만, 이 정책이 core 계약으로
   문서화되어 있지 않다.
6. crash, cancellation, timeout, lease expiry 이후의 상태 전환 책임이 불명확하다.

## 목표

- `leader-core`에 저장소 공통 history/audit 모델과 sink SPI를 정의한다.
- Exposed와 MongoDB가 같은 필드 의미와 상태 전환을 공유하게 한다.
- Redis/Hazelcast/ZooKeeper는 core 계약을 따를 수 있지만, 기본 구현 필수 대상에서
  제외하고 optional/best-effort audit으로 둔다.
- audit 저장 실패가 leader election 성공/실패, action 반환값, action 예외 전파를
  바꾸지 않는다는 정책을 명문화한다.
- #72의 `leaderId`와 충돌하지 않는 확장 지점을 둔다.

## 비목표

- 이 spec PR에서 backend 구현을 모두 추가하지 않는다.
- leader state snapshot 조회 API를 history 조회 API로 확장하지 않는다. #68 계열
  state 작업과 별도다.
- skipped/not-acquired 시도를 v1 history에 기록하지 않는다. 실패한 획득 시도는
  cardinality가 높고 저장소별 의미가 다르므로 metrics 영역으로 남긴다.
- Redis Streams, Kafka, OpenTelemetry event exporter 같은 외부 audit transport는
  v1 범위에 넣지 않는다.
- EXPIRED sweeper 구현은 v1 scope에서 제외한다. ACQUIRED 레코드 중 `lockedUntil < now`인
  것은 기능적으로 terminal이지만 DB에서 즉시 EXPIRED로 전환하지 않는다. 조회 쿼리는
  이 조건을 수동으로 필터해야 한다.

## 권장 설계

### 0. auditMap vs LeaderHistorySink — 계층 분리

PR #209에서 도입된 Redisson `auditLeaderId` `RMap`과 이번 `LeaderHistorySink`는
**목적이 다른 두 계층**이다. v1에서 공존하며 둘을 병합하거나 한쪽이 다른 쪽을
대체하지 않는다.

| 계층 | 위치 | 역할 | TTL |
|------|------|------|-----|
| `auditMap` (RMap) | leader-redis-redisson | in-flight leaderId live 상태 추적 | leaseTime + 5s |
| `LeaderHistorySink` | leader-core (SPI) | 완료/실패 영속 audit log | 없음 (별도 retention) |

이 계층 분리는 CLAUDE.md spec에 명시한다.

### 1. Core model

`leader-core`에 아래 타입을 추가한다.

#### 1-1. LeaderHistoryStatus

```kotlin
enum class LeaderHistoryStatus {
    ACQUIRED,
    COMPLETED,
    FAILED,
    EXPIRED,
}
```

#### 1-2. LeaderHistoryKind — LockIdentity.AnnotationKind 재사용

별도 `LeaderHistoryKind` enum을 정의하지 않는다.
`LockIdentity.AnnotationKind { SINGLE, GROUP }` 을 `LeaderLockHistoryRecord`의
`kind` 필드 타입으로 직접 사용한다. 이름 충돌과 의미 중복을 피하기 위함이다.

#### 1-3. LeaderLockHistoryRecord

```kotlin
data class LeaderLockHistoryRecord(
    val lockName: String,
    val kind: LockIdentity.AnnotationKind,
    val lockOwner: String? = null,
    val participantId: String? = null,   // reserved for #72; v1에서 미사용, nullable
    val token: String,
    val slotId: String? = null,
    val status: LeaderHistoryStatus,
    val startedAt: Instant,
    val lockedUntil: Instant,
    val finishedAt: Instant? = null,
    val durationMs: Long? = null,
    val errorType: String? = null,
    val errorMessage: String? = null,
    val metadata: Map<String, String> = emptyMap(),
) : Serializable {
    companion object : KLogging() {
        private const val serialVersionUID = 1L

        /** SafeLeaderHistoryRecorder가 강제하는 errorMessage 최대 바이트 크기 */
        const val MAX_ERROR_MESSAGE_BYTES = 512

        /** metadata map 최대 항목 수 */
        const val MAX_METADATA_KEYS = 16

        /** metadata 개별 값 최대 문자 수 */
        const val MAX_METADATA_VALUE_LENGTH = 256
    }

    init {
        // metadata는 생성 시점에 스냅샷을 찍는다 — caller가 MutableMap을 넘기고
        // 나중에 수정해도 record에 영향이 없도록 한다.
        // (data class 선언에서 Map 기본값은 불변이지만, caller가 mutableMapOf()를
        //  넘길 수 있으므로 adapter/SafeLeaderHistoryRecorder가 copy를 강제한다.)
    }
}
```

필드 의미:

- `lockName`: application-visible logical lock name.
- `kind`: `LockIdentity.AnnotationKind.SINGLE` 또는 `GROUP`. `LeaderHistoryKind`를
  별도 정의하지 않고 기존 enum 재사용.
- `lockOwner`: backend/node owner. 현재 Exposed `lockOwner`에 대응한다.
  hostname, IP, application-instance-id 등을 담는다.
- `participantId`: #72 `leaderId` 연동을 위한 확장 지점. v1에서는 nullable로 두고
  기본 로직에 쓰지 않는다. elector는 이 필드를 채우지 않는다.
- `token`: acquire 시 backend가 발급한 fencing/ownership token.
  **token은 전역적으로 유일하고 예측 불가능해야 한다**. UUID v4 (128 bit) 또는
  동등한 엔트로피(≥128 bit)의 문자열을 사용한다. `recordCompleted`/`recordFailed`
  전환 시 대상 레코드 검증에 사용한다.
  포맷 참고: Lettuce는 Base58 8자 토큰, Redisson은 permitId 문자열을 사용하며,
  Exposed는 UUID를 쓴다. backend-specific 포맷이며 opaque하게 취급한다.
- `slotId`: group lock의 slot/permit/member identity. 저장소별 native identity를
  문자열로 보존한다. 단일 리더는 null이다.
- `status`: `ACQUIRED -> COMPLETED | FAILED | EXPIRED` lifecycle.
- `startedAt`: acquire 성공 후 action 실행을 시작한 시각.
- `lockedUntil`: acquire 시점 기준 lease 만료 예정 시각. auto-extend 결과를
  완전히 추적하지 않는 best-effort 기준이다.
  **backend별 규칙**: native absolute expiry가 없는 backend(Redis, Hazelcast)는
  `lockedUntil = startedAt + leaseTime` 으로 계산해 채운다.
- `finishedAt`, `durationMs`: `COMPLETED`/`FAILED` 전환 시 채운다.
- `errorType`: `FAILED` 시 `exception::class.qualifiedName`. null 허용.
- `errorMessage`: `FAILED` 시 `exception.message`. **SafeLeaderHistoryRecorder가
  `MAX_ERROR_MESSAGE_BYTES` (512 B) 기준으로 UTF-8 바이트 단위로 truncate한다.
  stack trace를 포함하지 않는다. 개별 adapter는 truncate 책임을 지지 않는다.**
- `metadata`: backend-specific diagnostic hints. public query에 의존하지 않는
  보조 정보만. 키 ≤ `MAX_METADATA_KEYS`(16), 값 ≤ `MAX_METADATA_VALUE_LENGTH`(256자).
  **SafeLeaderHistoryRecorder가 초과 항목을 제거하고, adapter는 raw 값을 그대로
  전달한다.** 생성 시 `toMap()`으로 스냅샷.

#### 1-4. LeaderHistoryKey

```kotlin
data class LeaderHistoryKey(
    /**
     * backend가 id 기반 update를 지원하면 채운다.
     * null이면 (lockName, token, slotId)를 자연 키로 사용한다.
     * SafeLeaderHistoryRecorder는 key가 null일 때 recordCompleted/recordFailed를
     * 건너뛰지 않는다 — 자연 키로 update를 시도한다.
     */
    val historyId: String?,
    val lockName: String,
    val token: String,
    val slotId: String? = null,
) : Serializable {
    companion object : KLogging() {
        private const val serialVersionUID = 1L
    }
}
```

`historyId` null/non-null 계약:

| historyId | 의미 | 전환 전략 |
|-----------|------|-----------|
| non-null  | backend이 DB-generated id 반환 (Exposed JDBC/R2DBC) | `UPDATE WHERE id = ? AND token = ?` |
| null      | backend이 id를 반환하지 않음 (MongoDB, Noop 등) | `UPDATE WHERE lockName = ? AND token = ?` |

sink 구현은 자신이 지원하는 전환 전략을 선택한다. `SafeLeaderHistoryRecorder`는
어느 쪽이든 동일하게 호출하고 전략 선택은 sink에 위임한다.

### 2. Sink SPI

`leader-core`에 blocking/suspend 분리된 두 인터페이스를 정의한다.

```kotlin
interface LeaderHistorySink {
    /** 구현은 thread-safe해야 한다 — blocking, virtual-thread, coroutine elector가 동시 호출 가능. */
    fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey?
    fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long)
    /** error가 CancellationException이면 이 메서드를 호출하지 않는다 (호출 측 책임). */
    fun recordFailed(
        key: LeaderHistoryKey,
        finishedAt: Instant,
        durationMs: Long,
        error: Throwable? = null,
    )
}

interface SuspendLeaderHistorySink {
    /** 구현은 coroutine-safe해야 한다 — 동시 coroutine 호출 가능. */
    suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey?
    suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long)
    /** error가 CancellationException이면 이 메서드를 호출하지 않는다 (호출 측 책임). */
    suspend fun recordFailed(
        key: LeaderHistoryKey,
        finishedAt: Instant,
        durationMs: Long,
        error: Throwable? = null,
    )
}
```

elector가 `LeaderHistorySink`를 직접 사용하는 방법은 Spring auto-config 단계에서
명세한다. 선택적 의존성으로 주입하며, sink 없이도 elector는 정상 동작한다.

제공 타입:

- `NoopLeaderHistorySink` — 아무것도 기록하지 않는 구현.
- `NoopSuspendLeaderHistorySink` — suspend 버전.

#### 2-1. SafeLeaderHistoryRecorder

elector가 sink를 직접 호출하지 않고 이 helper를 통해 호출한다.

```kotlin
class SafeLeaderHistoryRecorder(
    private val sink: LeaderHistorySink,
    private val meterRegistry: MeterRegistry? = null,   // null이면 metrics skip
) {
    companion object : KLogging() {
        private const val COUNTER_SINK_FAILURES = "leader.history.sink.failures"
        private const val TAG_OPERATION = "operation"
    }

    fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? = try {
        sink.recordAcquired(sanitize(record))
    } catch (e: Exception) {
        handleLoss("recordAcquired", record, e)
        null
    }

    fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) {
        try {
            sink.recordCompleted(key, finishedAt, durationMs)
        } catch (e: Exception) {
            handleLoss("recordCompleted", null, e)
        }
    }

    /**
     * CancellationException은 이 메서드로 넘기지 않는다.
     * caller는 action 예외가 CancellationException인 경우 recordFailed를 호출하지 않아야 한다.
     */
    fun recordFailed(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long, error: Throwable? = null) {
        require(error !is CancellationException) {
            "CancellationException must not be passed to recordFailed — skip the call instead."
        }
        try {
            sink.recordFailed(key, finishedAt, durationMs, error)
        } catch (e: Exception) {
            handleLoss("recordFailed", null, e)
        }
    }

    private fun handleLoss(operation: String, record: LeaderLockHistoryRecord?, cause: Exception) {
        log.warn(cause) { "History sink loss: op=$operation record=$record" }
        meterRegistry?.counter(COUNTER_SINK_FAILURES, TAG_OPERATION, operation)?.increment()
    }

    private fun sanitize(record: LeaderLockHistoryRecord): LeaderLockHistoryRecord = record.copy(
        errorMessage = record.errorMessage?.truncateUtf8(LeaderLockHistoryRecord.MAX_ERROR_MESSAGE_BYTES),
        metadata = record.metadata.entries.take(LeaderLockHistoryRecord.MAX_METADATA_KEYS)
            .associate { (k, v) -> k to v.take(LeaderLockHistoryRecord.MAX_METADATA_VALUE_LENGTH) }
            .toMap(),
    )
}
```

`SuspendSafeLeaderHistoryRecorder`는 suspend 버전으로 동일 구조를 가진다.
suspend 버전에서 `CancellationException`은 **절대로 catch하지 않는다**:

```kotlin
class SuspendSafeLeaderHistoryRecorder(
    private val sink: SuspendLeaderHistorySink,
    private val meterRegistry: MeterRegistry? = null,
) {
    suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? {
        return try {
            sink.recordAcquired(sanitize(record))
        } catch (e: CancellationException) {
            throw e   // 절대 삼키지 않는다
        } catch (e: Exception) {
            handleLoss("recordAcquired", record, e)
            null
        }
    }

    suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) {
        try {
            sink.recordCompleted(key, finishedAt, durationMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleLoss("recordCompleted", null, e)
        }
    }

    suspend fun recordFailed(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long, error: Throwable? = null) {
        require(error !is CancellationException) {
            "CancellationException must not be passed to recordFailed."
        }
        try {
            sink.recordFailed(key, finishedAt, durationMs, error)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleLoss("recordFailed", null, e)
        }
    }

    // sanitize()와 handleLoss()는 blocking 버전과 동일
}
```

`leader.history.sink.failures` counter tag:

| tag | 값 |
|-----|----|
| `operation` | `recordAcquired` / `recordCompleted` / `recordFailed` |
| `sink_type` | sink class simple name (옵션, leader-micrometer 확장) |

이 counter는 `leader-micrometer`의 `MicrometerNames`에 등록하고,
auto-config는 `MeterRegistry` bean이 있을 때 optional inject한다.

#### 2-2. 성능 계약 (hot-path overhead)

sink 호출은 `runIfLeader` 내 acquire/release 경로에 포함된다.
**p99 overhead ≤ 1 ms** 를 목표로 한다. 이를 초과하는 sink(원격 DB 동기 쓰기 등)는
`withContext(Dispatchers.IO)` 또는 비동기 fire-and-forget을 sink 내부에서 책임진다.

in-memory sink(Noop, test double)는 `withContext(Dispatchers.IO)` 디스패치가 필요
없다. suspend sink 구현이 IO 비용을 발생시키는 경우에만 IO 디스패치를 추가한다.

벤치마크 요건: `leader-core` 또는 `leader-redis-lettuce`에 JMH 마이크로벤치마크를
추가하여 sink 포함 / 미포함 `runIfLeader` throughput을 측정한다.

### 3. 상태 전환 계약

```
ACQUIRED ─► COMPLETED  (action 정상 반환)
         ─► FAILED     (action 예외, CancellationException 제외)
         ─► EXPIRED    (ACQUIRED 레코드 중 lockedUntil < now — sweeper 처리, v1 out-of-scope)
```

- `ACQUIRED`: lock/slot acquire가 성공하고 action 실행을 시작하기 직전에 기록한다.
- `COMPLETED`: action이 정상 반환한 경우 기록한다. `null` 반환도 정상 완료다.
- `FAILED`: action이 예외로 종료된 경우 기록한다.
- `CancellationException` 및 `CompletableFuture.cancel()`은 `FAILED`가 아니다.
  cancellation은 caller-controlled control flow이므로 v1에서는 terminal transition을
  기록하지 않는다. 기존 `ACQUIRED` 레코드는 sweeper 또는 TTL 만료가 처리한다.
- `EXPIRED`: **v1 sweep 구현 out-of-scope.** `ACQUIRED` 레코드 중 `lockedUntil < now`인
  것은 기능적으로 terminal이지만 DB에서 즉시 EXPIRED로 전환하지 않는다.
  쿼리/UI 구현은 `status = ACQUIRED AND lockedUntil < now`를 EXPIRED로 간주해야 한다.
  sweeper 구현은 별도 이슈로 추적한다.

### 4. Exposed projection

기존 `LeaderLockHistoryTable`은 core model의 RDBMS projection으로 유지한다.

#### 4-1. 필요한 후속 변경

- `leader-exposed-core`의 `HistoryStatus`는 `LeaderHistoryStatus`로 이전한다.
  호환성은 `typealias HistoryStatus = LeaderHistoryStatus` 또는 deprecation wrapper로
  관리한다.
- `slot` integer 컬럼은 기존 호환을 위해 유지하되 core field 이름은 `slotId`로 둔다.
  Exposed v1 adapter는 numeric slot을 `slot`에 쓰고, string `slotId` 컬럼 추가 여부는
  migration blast radius를 따로 검토한다.
- `errorType`, `errorMessage`, `kind`, `participantId`, `metadata` 컬럼은 후속 schema
  migration에서 추가한다.

#### 4-2. Schema migration 규칙 (DDL 안전성)

모든 신규 컬럼은 **nullable, DEFAULT 없음**으로 추가한다.
이를 통해 구버전 코드가 신규 컬럼을 모르는 상태에서 실행되어도 기존 레코드에 영향이 없다.

- DDL 변경은 additive-only (v1): 컬럼 rename/type change 금지.
- 롤백 스크립트 (`ALTER TABLE DROP COLUMN ...`)를 PostgreSQL + MySQL 양쪽에서 검증해야
  migration PR 병합이 가능하다.
- `SchemaUtils.createMissingTablesAndColumns`는 개발/테스트 환경에서만 사용한다.
  **멀티 포드 프로덕션 배포에서는 사전 DDL 스크립트(또는 Flyway/Liquibase)를 사용한다.**
  동시 `ALTER TABLE` 실행은 PostgreSQL `pg_class` deadlock 위험이 있다.
- `ensureSchema` 가드 key는 JVM-local이어서 멀티 포드에서 각 포드가 독립적으로
  DDL을 시도한다. 이 동작이 production에서 안전하지 않음을 문서화한다.

retention: `startedAt < cutoff` batch delete를 기본으로 한다.
기본 cutoff = 30일. 설정 프로퍼티 `bluetape4k.leader.exposed.history.retentionDays`로
변경한다. batch delete는 1000건 단위로 분할하여 lock 경합을 줄인다.

### 5. MongoDB projection

MongoDB는 lock collection과 history collection을 분리한다.

권장 collection:

- single: `bluetape4k_leader_history`
- group: 같은 collection에 `kind = GROUP`, `slotId != null`로 저장한다.

권장 document:

```json
{
  "_id": "...",
  "lockName": "...",
  "kind": "SINGLE",
  "lockOwner": "...",
  "participantId": null,
  "token": "...",
  "slotId": null,
  "status": "ACQUIRED",
  "startedAt": "...",
  "lockedUntil": "...",
  "finishedAt": null,
  "durationMs": null,
  "errorType": null,
  "errorMessage": null,
  "metadata": {}
}
```

#### 5-1. Index 전략

MongoDB 4.2+ concurrent index build를 사용한다 (background flag 불필요).
MongoDB 4.0을 지원해야 하는 경우 `background: true` 명시.
인덱스 생성은 application startup 경로가 아닌 **별도 초기화 단계 또는 lazy 백그라운드
coroutine**으로 수행한다. 대용량 collection에서 startup blocking 방지.

권장 index:

- `{ lockName: 1, startedAt: -1 }` — 조회 기본 패턴
- `{ token: 1 }` — (lockName, token) 기반 update
- optional `{ _id: 1, token: 1 }` — update 조건 보조

#### 5-2. Retention TTL

**기본 TTL = 90일** (설정 프로퍼티 `bluetape4k.leader.mongodb.history.ttlDays`, Long).
retention을 비활성화(무제한)하려면 0 또는 음수로 설정하며, 이는 개발/테스트 전용이다.
프로덕션에서 TTL 비활성 시 startup WARNING log와 Micrometer gauge를 발행한다:

```
leader.history.mongodb.ttl.disabled (gauge, value=1.0)
```

TTL index on `startedAt`(기본) 또는 `finishedAt` (설정 가능).
이 index는 lock collection의 `expireAt` TTL과 목적이 다르다. 혼용 금지.

### 6. Redis/Hazelcast/ZooKeeper policy

- v1에서 built-in persistent history 구현을 요구하지 않는다.
- **PR #209 auditMap과의 관계**: Redis group elector의 Redisson `auditMap` (RMap)은
  in-flight live 상태 추적 목적이며, 이번 `LeaderHistorySink` SPI와 계층이 다르다.
  v1에서는 auditMap을 sink로 대체하지 않는다.
- backend가 외부에서 `LeaderHistorySink` 옵션을 주입받으면 best-effort로 기록한다.
  Redis/Hazelcast 기본 구현 없이도 동작해야 한다.
- backend-native audit transport를 추가하는 경우 core `LeaderLockHistoryRecord`
  semantics를 따라야 한다.

### 7. 테스트 계획 (v1 필수)

#### 7-1. 단위 테스트

| 테스트 | 검증 포인트 | 도구 |
|--------|------------|------|
| `SafeLeaderHistoryRecorder` sink 예외 catch | warn log + counter 증가, election 영향 없음 | MockK + `io.mockk.coEvery` |
| `SuspendSafeLeaderHistoryRecorder` CE rethrow | `assertFailsWith<CancellationException>` | JUnit 5 |
| `recordFailed` CE 파라미터 거부 | `require(error !is CancellationException)` → IllegalArgumentException | assertFailsWith |
| `sanitize()` errorMessage truncation | 512B 초과 → 잘림 | Kluent |
| `sanitize()` metadata 항목 제한 | 17개 → 16개로 감소 | Kluent |
| `LeaderHistoryKey.historyId` null 계약 | null 시 자연 키 경로 선택 | unit test |

#### 7-2. 동시성 테스트

- **blocking sink**: `MultithreadingTester` (workers=8, rounds=50)로 `SafeLeaderHistoryRecorder.recordAcquired()` 동시 호출 → thread-safety 검증
- **suspend sink**: `SuspendedJobTester` 또는 `StructuredTaskScopeTester`로 `SuspendSafeLeaderHistoryRecorder` 동시 호출
- 직접 `Thread` / `Executors` / `coroutineScope.launch` 사용 금지 — bluetape4k 표준 도구 사용

#### 7-3. 통합 테스트

| 모듈 | 인프라 | 검증 |
|------|--------|------|
| `leader-exposed-jdbc` | Testcontainers PostgreSQL | ACQUIRED→COMPLETED, ACQUIRED→FAILED, null key 경로 |
| `leader-exposed-r2dbc` | Testcontainers PostgreSQL (R2DBC driver) | 동일 |
| `leader-mongodb` | Testcontainers MongoDB | document 생성/update/TTL |

#### 7-4. sink 장애 경로

| 시나리오 | 기대 동작 |
|----------|-----------|
| sink DB 다운 시 acquire | election 성공, warn log, counter 증가, action 실행됨 |
| sink 응답 지연 (>1ms p99) | 벤치마크로 측정, overhead ≥1ms 시 비동기 전환 검토 |
| sink recordAcquired null 반환 | recordCompleted/recordFailed skip, election 계속 |

#### 7-5. schema migration 테스트

- 신규 컬럼 nullable DDL이 PostgreSQL + MySQL에서 기존 데이터 손상 없이 적용되는지
  Testcontainers DB에서 검증한다.
- 롤백 스크립트(`ALTER TABLE DROP COLUMN`)를 동일 DB에서 실행하고 기존 레코드가
  유지됨을 검증한다.

## Alternatives

### Option A: Core model + sink SPI, backend projections follow it

권장 옵션. semantic drift를 선제 방지한다.

### Option B: Keep Exposed-only history

거부. MongoDB 등이 독자 audit vocabulary를 발명하는 위험을 방치한다.

### Option C: Full event-sourcing stream

v1 거부. skipped 시도, watchdog extension, 릴리스 이벤트까지 포함하면 volume과
query 설계 범위가 크게 늘어난다. v1 product 니즈는 acquisition lifecycle audit이다.

## Risks

- `slot` type migration: Exposed에는 integer `slot`, core에는 portable string `slotId`.
  schema 변경 없이 spec-only PR을 유지하고 migration은 별도 계획.
- enum 이전: `HistoryStatus` → `LeaderHistoryStatus`. 호환 alias/deprecation 경로 필수.
- cancellation semantics: `ACQUIRED` 레코드를 sweeper가 처리하도록 남겨두는 방식은
  기존 lesson과 일치한다. 일부 사용자는 `CANCELLED` 상태를 기대할 수 있다.
  concrete product 요건이 생기기 전까지 defer.
- auto-extend drift: `lockedUntil`이 best-effort 값. watchdog 확장 이벤트를 추적하지
  않으면 `lockedUntil < finishedAt` 일 수 있다.
- error field 민감도: errorMessage가 PII를 포함할 수 있다. truncate (512 B) +
  stack trace 제외가 SafeLeaderHistoryRecorder 책임임을 명시.
- token 포맷: 저장소별 포맷(Base58, UUID, permitId)이 다르다. core contract는 opaque
  취급하되, 엔트로피 ≥128 bit 요건은 backend별 구현 선택에 위임한다.

## Acceptance criteria

- #50과 #72 관계가 독립적으로 문서화되었고 `leaderId` naming guard가 있다.
- core history record/status/sink 계약이 정의되었다.
- `LeaderHistoryKind` 미사용 — `LockIdentity.AnnotationKind` 직접 참조.
- `LeaderLockHistoryRecord`와 `LeaderHistoryKey`가 `Serializable`이다.
- `SafeLeaderHistoryRecorder` (blocking + suspend)의 시그니처, CE 재throw 규칙,
  truncation 책임, Micrometer 카운터가 spec에 포함되었다.
- token 엔트로피 요건(≥128 bit)이 명시되었다.
- schema migration 규칙(nullable only, additive-only, 롤백 스크립트, 멀티포드 DDL 제한)이
  명시되었다.
- EXPIRED sweeper가 v1 out-of-scope으로 명시되었고 쿼리 대안이 설명되었다.
- MongoDB index 전략, TTL 기본값(90일), startup WARNING이 명시되었다.
- `leader.history.sink.failures` Micrometer counter가 spec에 포함되었다.
- auditMap (live state) vs LeaderHistorySink (persistent log) 계층 분리가 명시되었다.
- 테스트 계획(단위, 동시성, 통합, 장애 경로, DDL 롤백)이 포함되었다.
- 핫패스 overhead 목표(p99 ≤ 1 ms)와 벤치마크 요건이 명시되었다.
- Exposed projection, MongoDB projection, Redis/HZ/ZK policy가 명시되었다.
- Implementation은 follow-up 태스크로 분리한다.

## Appendix — Review Iteration Log

### Round 1 (2026-05-13)

Phase 1 agents: user/caller, developer, security, ops/SRE (4 × sonnet)
6-tier advisor: claude-code advisor

| Tier | P0 | P1 | P2 | P3 |
|------|----|----|-----|-----|
| 1 Security | 0 | 2 | 4 | 0 |
| 2 Ops/SRE | 0 | 4 | 4 | 0 |
| 3 Structural | 0 | 2 | 2 | 0 |
| 4 Kotlin | 0 | 2 | 2 | 0 |
| 5 Tests/silent fail | 0 | 3 | 2 | 0 |
| 6 Perf/stability | 0 | 2 | 2 | 0 |
| **합계** | **0** | **15** | **16** | **0** |

적용 내용:
- [Sec P1×2] errorMessage truncation → SafeLeaderHistoryRecorder 책임; token 엔트로피 ≥128 bit
- [Ops P1×4] schema migration 롤백/nullable/멀티포드 규칙; EXPIRED sweeper out-of-scope 명시;
  MongoDB index 전략; `leader.history.sink.failures` counter 추가
- [Struct P1×2] `LeaderHistoryKind` → `LockIdentity.AnnotationKind` 재사용;
  `SafeLeaderHistoryRecorder` 전체 시그니처 추가
- [Kotlin P1×2] `Serializable` + serialVersionUID; CE exclusion in recordFailed
- [Tests P1×3] 테스트 계획 섹션 신규 추가 (단위/동시성/통합/장애/DDL)
- [Perf P1×2] p99 ≤ 1ms overhead budget; IO dispatch 선택 기준

Spec revision commit hash: (pending — Round 2 dispatch 전 커밋)
