# Issue #50 leader history/audit common contract design

> Issue: #50 | Type: A Full Design, spec-only first | Date: 2026-05-12
> Rev: 3 (2026-05-13) — 2-R Round 2 P1×14 반영

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
- `LeaderLockHistoryTable` 현재 필드:
  `id` (Long PK), `lockName`, `lockOwner`, `token`, `slot`, `lockedUntil`, `status`,
  `startedAt`, `finishedAt`, `durationMs`.
- Exposed JDBC/R2DBC는 `recordAcquired()`가 history id(Long)를 반환하고,
  완료/실패 전환은 `(id, token)`으로 대상 레코드를 식별한다.
- Exposed 구현은 audit 저장 실패를 warn log 후 무시한다. 리더 선출 결과나 action
  결과는 audit 저장 성공 여부에 의존하지 않는다.
- MongoDB는 lock collection만 있고 history collection이 없다.
- **PR #209 (feat/leader-group-leaderid-72)**: Redisson group elector에
  `auditLeaderId` → `RMap` 기록을 추가했다. 이 map은 in-flight live 상태 추적용
  단기 TTL 구조이며, 이번 spec에서 설계하는 영속 audit log와 **계층 분리**된 역할을
  가진다.

## 문제

1. 공통 history 상태와 필드 의미가 없어 저장소별 audit 구현이 서로 달라질 수 있다.
2. Exposed의 `HistoryStatus`가 `leader-exposed-core`에 있어 MongoDB 등 다른 모듈이
   재사용할 수 없다.
3. group slot 식별자가 저장소마다 다르다.
4. audit 저장 실패가 리더 선출에 영향을 주지 않아야 하지만, 이 정책이 core 계약으로
   문서화되어 있지 않다.
5. crash, cancellation, timeout, lease expiry 이후의 상태 전환 책임이 불명확하다.

## 목표

- `leader-core`에 저장소 공통 history/audit 모델과 sink SPI를 정의한다.
- Exposed와 MongoDB가 같은 필드 의미와 상태 전환을 공유하게 한다.
- Redis/Hazelcast/ZooKeeper는 optional/best-effort audit으로 둔다.
- audit 저장 실패가 leader election 성공/실패, action 반환값, action 예외 전파를
  바꾸지 않는다는 정책을 명문화한다.
- #72의 `leaderId`와 충돌하지 않는 확장 지점을 둔다.

## 비목표

- 이 spec PR에서 backend 구현을 모두 추가하지 않는다.
- leader state snapshot 조회 API를 history 조회 API로 확장하지 않는다.
- skipped/not-acquired 시도를 v1 history에 기록하지 않는다.
- Redis Streams, Kafka, OpenTelemetry event exporter 같은 외부 audit transport는
  v1 범위에 넣지 않는다.
- EXPIRED sweeper 구현은 v1 scope에서 제외한다 (별도 이슈로 추적).
  ACQUIRED 레코드 중 `lockedUntil < now`인 것은 기능적으로 terminal이지만
  DB에서 즉시 EXPIRED로 전환하지 않는다.

## 권장 설계

### 0. auditMap vs LeaderHistorySink — 계층 분리

PR #209에서 도입된 Redisson `auditLeaderId` `RMap`과 이번 `LeaderHistorySink`는
**목적이 다른 두 계층**이다. v1에서 공존하며 둘을 병합하거나 한쪽이 다른 쪽을
대체하지 않는다.

| 계층 | 위치 | 역할 | TTL |
|------|------|------|-----|
| `auditMap` (RMap) | leader-redis-redisson | in-flight leaderId live 상태 추적 | leaseTime + 5s |
| `LeaderHistorySink` | leader-core (SPI) | 완료/실패 영속 audit log | 없음 (별도 retention) |

두 계층이 모순되는 경우(auditMap이 live라고 말하지만 sink는 FAILED 기록) 운영 팀은
sink 레코드를 authority로 사용한다. auditMap은 단기 live 상태용이며 영속 감사 목적이
아니다.

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
`kind` 필드 타입으로 직접 사용한다.

#### 1-3. LeaderLockHistoryRecord

```kotlin
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.time.Instant

data class LeaderLockHistoryRecord private constructor(
    val lockName: String,
    val kind: LockIdentity.AnnotationKind,
    val lockOwner: String? = null,
    val participantId: String? = null,   // reserved for #72; v1에서 미사용
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

        /** 팩토리 함수 — 검증 + metadata 방어 복사를 수행한다. */
        operator fun invoke(
            lockName: String,
            kind: LockIdentity.AnnotationKind,
            lockOwner: String? = null,
            participantId: String? = null,
            token: String,
            slotId: String? = null,
            status: LeaderHistoryStatus,
            startedAt: Instant,
            lockedUntil: Instant,
            finishedAt: Instant? = null,
            durationMs: Long? = null,
            errorType: String? = null,
            errorMessage: String? = null,
            metadata: Map<String, String> = emptyMap(),
        ): LeaderLockHistoryRecord {
            lockName.requireNotBlank("lockName")
            token.requireNotBlank("token")
            return LeaderLockHistoryRecord(
                lockName = lockName,
                kind = kind,
                lockOwner = lockOwner,
                participantId = participantId,
                token = token,
                slotId = slotId,
                status = status,
                startedAt = startedAt,
                lockedUntil = lockedUntil,
                finishedAt = finishedAt,
                durationMs = durationMs,
                errorType = errorType,
                errorMessage = errorMessage,
                metadata = metadata.toMap(),  // 방어 복사
            )
        }
    }
}
```

`constructor`를 `private`으로 막고 `companion object operator fun invoke`를 통해서만
생성한다. 이를 통해 `requireNotBlank` 검증과 `metadata.toMap()` 방어 복사가 항상
실행된다.

필드 의미:

- `lockName`: application-visible logical lock name.
- `kind`: `LockIdentity.AnnotationKind.SINGLE` 또는 `GROUP`.
- `lockOwner`: backend/node owner (hostname, IP, application-instance-id 등).
- `participantId`: #72 `leaderId` 연동을 위한 확장 지점. v1 미사용.
- `token`: acquire 시 backend가 발급한 fencing/ownership token.
  **전역적으로 유일하고 예측 불가능해야 한다 — ≥128 bit 엔트로피 필수.**
  UUID v4 또는 Base58 22자 이상 사용. Lettuce 현재 Base58 8자(≈47 bit)는 이 요건을
  충족하지 못하므로 **즉시 업그레이드가 필요하다** (이 spec PR과 함께 처리).
  포맷은 backend-specific이며 core contract에서 opaque하게 취급한다.

  **레거시 token 호환 정책 (Lettuce 업그레이드 전환 시):**
  - 신규 acquire부터 128-bit token 사용. 이전 8자 token으로 기록된 기존 ACQUIRED 레코드는
    그대로 유지하며 별도 마이그레이션하지 않는다.
  - 기존 레코드의 `recordCompleted`/`recordFailed` 전환은 해당 레코드의 8자 token으로
    진행한다 — 자연 키 `(lockName, token)` 매칭이 그대로 동작.
  - 레거시 레코드는 `lockedUntil` 만료 후 EXPIRED 처리되므로 영구 잔존하지 않는다.
  - 보안 영향: 8자 token(47-bit)은 audit record 위조에 취약하나, 만료 시간 내 단기간만
    유효하므로 실질 위험이 제한적이다. 업그레이드 후 신규 레코드는 영향 없음.
- `slotId`: group lock의 slot/permit/member identity (단일 리더는 null).
- `status`: `ACQUIRED -> COMPLETED | FAILED | EXPIRED` lifecycle.
- `startedAt`: acquire 성공 후 action 실행 시작 시각.
- `lockedUntil`: lease 만료 예정 시각 (best-effort).
  Native absolute expiry가 없는 backend(Redis, Hazelcast)는
  `lockedUntil = startedAt + leaseTime`으로 계산해 채운다.
- `finishedAt`, `durationMs`: `COMPLETED`/`FAILED` 전환 시 채운다.
- `errorType`: `SafeLeaderHistoryRecorder`가 `exception::class.qualifiedName`으로
  파생한다. 개별 adapter가 파생하지 않는다.
- `errorMessage`: `SafeLeaderHistoryRecorder`가 `exception.message`를 파생하고
  `MAX_ERROR_MESSAGE_BYTES`(512 B, UTF-8 바이트 기준)로 truncate한다.
  **stack trace를 포함하지 않는다.** 개별 adapter는 truncate 책임을 지지 않는다.
- `metadata`: backend-specific hints. 생성 시 `toMap()`으로 스냅샷됨.
  키 ≤ `MAX_METADATA_KEYS`(16), 값 ≤ `MAX_METADATA_VALUE_LENGTH`(256자).

#### 1-4. LeaderHistoryKey

```kotlin
data class LeaderHistoryKey(
    /**
     * Exposed JDBC/R2DBC: auto-increment Long PK.
     * 다른 backend: null.
     * `id != null`이면 `UPDATE WHERE id = ? AND token = ?` 전략 사용.
     */
    val id: Long? = null,
    /**
     * Application-level natural id (예: UUID 문자열 또는 backend 발급 식별자).
     * null이면 (lockName, token) 조합을 자연 키로 사용한다.
     */
    val historyId: String? = null,
    val lockName: String,
    val token: String,
    val slotId: String? = null,
) : Serializable {
    companion object : KLogging() {
        private const val serialVersionUID = 1L
    }
}
```

Update 전략 (우선순위 순):

| 조건 | 전환 전략 |
|------|-----------|
| `id != null` | `UPDATE WHERE id = ? AND token = ?` (Exposed JDBC/R2DBC) |
| `historyId != null` | `UPDATE WHERE historyId = ? AND token = ?` |
| 둘 다 null | `UPDATE WHERE lockName = ? AND token = ?` (token이 전역 유일이므로 충분) |

token ≥128 bit 엔트로피 보장 하에 `(lockName, token)` 조합은 충돌 없이 단일 레코드를
식별한다. `slotId`는 조회/audit 목적으로만 사용하며 update 조건에 포함하지 않는다.

### 2. Sink SPI

```kotlin
interface LeaderHistorySink {
    /**
     * 구현은 thread-safe해야 한다 — blocking/virtual-thread/coroutine elector 동시 호출 가능.
     *
     * @return null이면 acquire 기록 실패 — caller는 이후 recordCompleted/recordFailed를 skip해야 한다.
     */
    fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey?

    fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long)

    /**
     * CancellationException은 이 메서드로 전달하지 않는다 (호출 측 책임).
     */
    fun recordFailed(
        key: LeaderHistoryKey,
        finishedAt: Instant,
        durationMs: Long,
        errorType: String?,
        errorMessage: String?,
    )
}

interface SuspendLeaderHistorySink {
    /** 구현은 coroutine-safe해야 한다. */
    suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey?

    suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long)

    /** CancellationException은 이 메서드로 전달하지 않는다. */
    suspend fun recordFailed(
        key: LeaderHistoryKey,
        finishedAt: Instant,
        durationMs: Long,
        errorType: String?,
        errorMessage: String?,
    )
}
```

`recordFailed`가 `Throwable?` 대신 이미 파생된 `errorType: String?`, `errorMessage: String?`을
받는다. `SafeLeaderHistoryRecorder`가 파생과 truncation을 책임지므로 sink adapter는
raw `Throwable`을 처리할 필요가 없다.

제공 타입:

- `NoopLeaderHistorySink` — 아무것도 기록하지 않는 구현.
- `NoopSuspendLeaderHistorySink` — suspend 버전.

#### 2-1. Wiring — elector와 sink 연결 방법

각 `LeaderElector` 구현은 **optional constructor parameter**로 `SafeLeaderHistoryRecorder?`를
받는다. null이면 audit 기록 없이 동작한다.

```kotlin
// 예시 (blocking elector)
class ExposedJdbcLeaderElector(
    private val options: ExposedJdbcLeaderElectionOptions,
    private val historyRecorder: SafeLeaderHistoryRecorder? = null,  // optional
) : LeaderElector {
    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        val record = LeaderLockHistoryRecord(...)
        val key = historyRecorder?.recordAcquired(record)
        return try {
            val result = action()
            historyRecorder?.recordCompleted(key ?: return result, ...)
            result
        } catch (e: CancellationException) {
            // CancellationException은 FAILED 기록 없이 재throw.
            // "runIfLeader 절대 throw 않음" 계약의 예외: CE는 structured concurrency
            // 신호이므로 반드시 caller에게 전파한다. lock 미획득(null 반환) 계약과
            // 구별되는 유일한 예외 경우다.
            throw e
        } catch (e: Exception) {
            historyRecorder?.recordFailed(key ?: return null, ..., e)
            null   // action 예외는 null 반환으로 흡수
        }
    }
}
```

**`runIfLeader` throw 계약 명세:**

| 상황 | 동작 |
|------|------|
| lock 미획득 (contention) | `null` 반환, 절대 throw 없음 |
| action 예외 (`Exception`) | `null` 반환, 절대 throw 없음 |
| `CancellationException` | **rethrow** — structured concurrency 신호, 유일한 예외 |

CLAUDE.md "runIfLeader 절대 throw하지 않음"은 lock 미획득과 action 예외에 적용된다.
`CancellationException`은 caller의 coroutine scope 취소 신호이므로 반드시 전파해야 한다.
이것이 두 계약이 모순 없이 공존하는 방식이다.

**Spring 환경**: `LeaderElectionAutoConfiguration`이 `LeaderHistorySink` bean이 있으면
`SafeLeaderHistoryRecorder`를 생성해 elector에 주입한다.

**비-Spring 환경**: caller가 직접 `SafeLeaderHistoryRecorder(sink)`를 생성해
elector constructor에 전달한다.

sink 없이 elector를 생성하면 audit 기록이 없어도 동작은 정상이다.

#### 2-2. SafeLeaderHistoryRecorder (leader-core)

`MeterRegistry`를 포함하지 않는다. `leader-micrometer`에서 별도 클래스가 래핑한다.

```kotlin
class SafeLeaderHistoryRecorder(
    private val sink: LeaderHistorySink,
) {
    companion object : KLogging()

    fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? = try {
        sink.recordAcquired(sanitize(record))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(e) { "History sink loss: op=recordAcquired lockName=${record.lockName.sanitizeForLog()}" }
        null
    }

    fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) {
        try {
            sink.recordCompleted(key, finishedAt, durationMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "History sink loss: op=recordCompleted lockName=${key.lockName.sanitizeForLog()}" }
        }
    }

    /**
     * `error`에서 errorType, errorMessage를 파생하고 truncate한 후 sink를 호출한다.
     * CancellationException을 error로 전달하면 안 된다 — caller 책임.
     * 잘못된 호출(CE 전달)은 로그하고 흡수한다 — IAE를 전파하지 않는다.
     */
    fun recordFailed(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long, error: Throwable? = null) {
        if (error is CancellationException) {
            log.warn { "recordFailed called with CancellationException — skip. lockName=${key.lockName.sanitizeForLog()}" }
            return
        }
        val errorType = error?.let { it::class.qualifiedName }
        val errorMessage = error?.message?.truncateUtf8(LeaderLockHistoryRecord.MAX_ERROR_MESSAGE_BYTES)
        try {
            sink.recordFailed(key, finishedAt, durationMs, errorType, errorMessage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "History sink loss: op=recordFailed lockName=${key.lockName.sanitizeForLog()}" }
        }
    }

    private fun sanitize(record: LeaderLockHistoryRecord): LeaderLockHistoryRecord = record.copy(
        errorMessage = record.errorMessage?.truncateUtf8(LeaderLockHistoryRecord.MAX_ERROR_MESSAGE_BYTES),
        metadata = record.metadata.entries
            .take(LeaderLockHistoryRecord.MAX_METADATA_KEYS)
            .associate { (k, v) ->
                k.take(64).sanitizeForLog() to v.take(LeaderLockHistoryRecord.MAX_METADATA_VALUE_LENGTH)
            },
    )

    /** log injection 방지: \n, \r, ASCII control chars 제거 */
    private fun String.sanitizeForLog(): String =
        replace(Regex("\\p{Cntrl}"), "?")
}
```

`CancellationException`은 blocking 버전에서도 **절대 삼키지 않는다** — rethrow.
`recordFailed`로 CE가 전달되는 경우(프로그래밍 오류)는 audit-isolation 계약에 따라
IAE를 전파하지 않고 warn log 후 skip한다.

#### 2-3. SuspendSafeLeaderHistoryRecorder (leader-core)

suspend 버전. `sanitize()`와 `sanitizeForLog()`는 `SafeLeaderHistoryRecorder`와
동일한 `internal fun`으로 추출해 공유한다 (top-level 또는 shared object).

```kotlin
class SuspendSafeLeaderHistoryRecorder(
    private val sink: SuspendLeaderHistorySink,
) {
    companion object : KLogging()

    suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? {
        return try {
            sink.recordAcquired(sanitize(record))
        } catch (e: CancellationException) {
            throw e   // 절대 삼키지 않는다
        } catch (e: Exception) {
            log.warn(e) { "History sink loss: op=recordAcquired lockName=${record.lockName.sanitizeForLog()}" }
            null
        }
    }

    suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) {
        try {
            sink.recordCompleted(key, finishedAt, durationMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "History sink loss: op=recordCompleted lockName=${key.lockName.sanitizeForLog()}" }
        }
    }

    suspend fun recordFailed(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long, error: Throwable? = null) {
        if (error is CancellationException) {
            log.warn { "recordFailed called with CE — skip. lockName=${key.lockName.sanitizeForLog()}" }
            return
        }
        val errorType = error?.let { it::class.qualifiedName }
        val errorMessage = error?.message?.truncateUtf8(LeaderLockHistoryRecord.MAX_ERROR_MESSAGE_BYTES)
        try {
            sink.recordFailed(key, finishedAt, durationMs, errorType, errorMessage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "History sink loss: op=recordFailed lockName=${key.lockName.sanitizeForLog()}" }
        }
    }
}
```

#### 2-4. MicrometerSafeLeaderHistoryRecorder (leader-micrometer)

`leader-micrometer`에 별도 클래스로 정의한다. `leader-core`의
`SafeLeaderHistoryRecorder`를 delegate로 사용하거나 subtype으로 확장한다.

```kotlin
class MicrometerSafeLeaderHistoryRecorder(
    sink: LeaderHistorySink,
    private val meterRegistry: MeterRegistry,
) : SafeLeaderHistoryRecorder(sink) {

    private fun counter(operation: String) =
        meterRegistry.counter(
            "leader.history.sink.failures",
            "operation", operation,
            "sink_type", sink::class.simpleName ?: "unknown",
        )

    // recordAcquired/recordCompleted/recordFailed를 override해
    // 부모 호출 후 null 반환 또는 exception 시 counter.increment() 호출
}
```

`leader.history.sink.failures` counter tag:

| tag | 값 |
|-----|----|
| `operation` | `recordAcquired` / `recordCompleted` / `recordFailed` |
| `sink_type` | sink class simple name |

이 counter는 `leader-micrometer`의 `MicrometerNames`에 등록한다.
auto-config는 `MeterRegistry` bean과 `LeaderHistorySink` bean이 모두 있을 때
`MicrometerSafeLeaderHistoryRecorder`를 생성한다.
`MeterRegistry` bean이 없으면 `SafeLeaderHistoryRecorder`를 사용한다.

#### 2-5. truncateUtf8 유틸리티 (필수 사전 조건)

`bluetape4k-support`(`io.bluetape4k.support.StringSupport.kt`)에
`fun String.truncateUtf8(maxBytes: Int): String`을 추가해야 한다.

올바른 구현 (surrogate pair 경계를 유지):

```kotlin
fun String.truncateUtf8(maxBytes: Int): String {
    val bytes = toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return this
    // maxBytes까지 자른 후 유효한 UTF-8로 디코드
    var end = maxBytes
    // continuation byte (10xxxxxx)가 시작 위치에 오지 않도록 후퇴
    while (end > 0 && (bytes[end - 1].toInt() and 0xC0) == 0x80) end--
    return bytes.copyOf(end).toString(Charsets.UTF_8)
}
```

이 함수는 이 spec PR과 같은 시점에 `bluetape4k-support`에 추가하거나,
`leader-core`에 `internal fun`으로 포함한다.

#### 2-6. 성능 계약 (hot-path overhead)

sink 호출은 `runIfLeader` 내 acquire/release 경로에 포함된다.
**p99 overhead ≤ 1 ms**를 목표로 한다. 이를 초과하는 sink(원격 DB 동기 쓰기)는
`withContext(Dispatchers.IO)` 또는 비동기를 sink 내부에서 책임진다.

in-memory sink는 IO 디스패치가 필요 없다.

벤치마크 요건: `leader-core` 또는 `leader-redis-lettuce`에 JMH 마이크로벤치마크를
추가해 sink 포함/미포함 `runIfLeader` throughput을 측정한다.

### 3. 상태 전환 계약

```
ACQUIRED ─► COMPLETED  (action 정상 반환)
         ─► FAILED     (action 예외, CancellationException 제외)
         ─► EXPIRED    (ACQUIRED 레코드 중 lockedUntil < now — sweeper, v1 out-of-scope)
```

- `CancellationException` 및 `CompletableFuture.cancel()`은 `FAILED`가 아니다.
- `EXPIRED` sweeper: v1 out-of-scope. 쿼리/UI는 `status = ACQUIRED AND lockedUntil < now`를
  EXPIRED로 간주해야 한다.
- `effectiveStatus(record, now)` helper:
  ```kotlin
  fun LeaderLockHistoryRecord.effectiveStatus(now: Instant = Instant.now()): LeaderHistoryStatus =
      if (status == LeaderHistoryStatus.ACQUIRED && lockedUntil < now) LeaderHistoryStatus.EXPIRED
      else status
  ```
  이 helper를 `leader-core`에 top-level extension으로 추가해 모든 backend 쿼리에서
  일관된 결과를 얻는다.

### 4. Exposed projection

기존 `LeaderLockHistoryTable`은 core model의 RDBMS projection으로 유지한다.

#### 4-1. 필요한 후속 변경

- `leader-exposed-core`의 `HistoryStatus`는 `LeaderHistoryStatus`로 이전한다.
  호환성: `typealias HistoryStatus = LeaderHistoryStatus`.
- `LeaderLockHistoryTable.id` (Long PK) → `LeaderHistoryKey.id`에 전달한다.
- `errorType`, `errorMessage`, `kind`, `participantId`, `metadata` 컬럼은 후속 schema
  migration에서 추가한다.

#### 4-2. Schema migration 규칙 (DDL 안전성)

**지원 DB**: PostgreSQL 12+, MySQL 8.0+

모든 신규 컬럼은 **nullable, DEFAULT 없음**으로 추가한다.

```sql
-- PostgreSQL — lock_timeout으로 DDL을 time-bound
SET lock_timeout = '3s';
ALTER TABLE leader_lock_history ADD COLUMN IF NOT EXISTS error_type    VARCHAR(255);
ALTER TABLE leader_lock_history ADD COLUMN IF NOT EXISTS error_message VARCHAR(512);
ALTER TABLE leader_lock_history ADD COLUMN IF NOT EXISTS kind          VARCHAR(32);
ALTER TABLE leader_lock_history ADD COLUMN IF NOT EXISTS participant_id VARCHAR(255);
ALTER TABLE leader_lock_history ADD COLUMN IF NOT EXISTS metadata      TEXT;

-- MySQL 8.0+ — ALGORITHM=INSTANT으로 테이블 rebuild 없이 즉시 적용
ALTER TABLE leader_lock_history
    ADD COLUMN error_type     VARCHAR(255)  NULL,
    ADD COLUMN error_message  VARCHAR(512)  NULL,
    ADD COLUMN kind           VARCHAR(32)   NULL,
    ADD COLUMN participant_id VARCHAR(255)  NULL,
    ADD COLUMN metadata       TEXT          NULL,
    ALGORITHM=INSTANT;
```

- DDL은 additive-only (v1): 컬럼 rename/type change 금지.
- 롤백 스크립트(`ALTER TABLE DROP COLUMN ...`)를 PostgreSQL + MySQL 양쪽에서 검증해야
  migration PR 병합이 가능하다.
- `SchemaUtils.createMissingTablesAndColumns`는 **개발/테스트 전용**. 멀티 포드 프로덕션
  배포에서는 사전 Flyway/Liquibase 스크립트를 사용한다. 동시 `ALTER TABLE` 실행은
  PostgreSQL `pg_class` deadlock 위험이 있다.
- Migration 파일 위치 예시: `src/main/resources/db/migration/V202506130001__add_history_audit_columns.sql`

**Retention**: `startedAt < cutoff` batch delete. 기본 cutoff = 30일.
설정 프로퍼티 `bluetape4k.leader.exposed.history.retentionDays` (기본 30L).
batch delete는 1000건 단위로 분할해 lock 경합을 줄인다.
**Retention job 소유자**: Spring `@Scheduled`(cron) bean 또는 외부 cron.
Spring Boot 환경에서는 `LeaderAopAutoConfiguration`이 옵션 bean으로 제공한다.

### 5. MongoDB projection

MongoDB는 lock collection과 history collection을 분리한다.

권장 collection: `bluetape4k_leader_history`

권장 document schema:

```json
{
  "_id": "ObjectId",
  "historyId": "UUID or null",
  "lockName": "...",
  "kind": "SINGLE",
  "lockOwner": "...",
  "participantId": null,
  "token": "...",
  "slotId": null,
  "status": "ACQUIRED",
  "startedAt": "ISODate",
  "lockedUntil": "ISODate",
  "finishedAt": null,
  "durationMs": null,
  "errorType": null,
  "errorMessage": null,
  "metadata": {}
}
```

#### 5-1. Index 전략

MongoDB 4.2+ concurrent index build를 사용한다 (background 불필요).
MongoDB 4.0 지원 시 `background: true` 명시.

인덱스 생성은 application startup 경로가 아닌 **별도 lazy 백그라운드 coroutine**으로
수행한다. 대용량 collection에서 startup blocking 방지.

권장 index:

```javascript
{ lockName: 1, startedAt: -1 }   // 기본 조회 패턴
{ token: 1 }                      // (lockName, token) 기반 update
```

**Index 상태 Micrometer gauge**:

```
leader.history.mongodb.index.state  (gauge)
  -1 = 빌드 실패
   0 = 빌드 중
   1 = 준비 완료
```

Index 빌드 실패 시: 지수 백오프, 최대 3회 재시도 후 ERROR log.
빌드 중에는 COLLSCAN이 발생할 수 있으므로 SRE는 이 gauge를 모니터링한다.

TTL index 필드 변경(`startedAt` → `finishedAt`)은 drop+recreate가 필요하다.
이는 additive change가 아니므로 별도 migration 절차를 따른다.

#### 5-2. Retention TTL

**기본 TTL = 90일** (`bluetape4k.leader.mongodb.history.ttlDays: Long = 90`).
TTL 비활성(0 이하) 시 startup WARNING log + Micrometer gauge:

```kotlin
Gauge.builder("leader.history.mongodb.ttl.disabled") { if (config.ttlDays <= 0) 1.0 else 0.0 }
    .register(registry)
```

이 gauge는 live supplier로 등록해 runtime config 변경 시 즉시 반영된다.
TTL 비활성은 개발/테스트 전용이다.

Exposed(30일) vs MongoDB(90일) 기본값 차이: RDBMS row 비용과 MongoDB document 비용 차이에
기인한다. 두 backend를 함께 사용하는 경우 동일한 값으로 맞출 것을 권고한다.

### 6. Redis/Hazelcast/ZooKeeper policy

- v1에서 built-in persistent history 구현을 요구하지 않는다.
- **PR #209 auditMap 계층 분리**: Redisson `auditMap`은 live 상태 추적 목적이며,
  이번 `LeaderHistorySink` SPI와 계층이 다르다. v1에서 공존.
- backend가 외부에서 `LeaderHistorySink`를 주입받으면 best-effort로 기록한다.
- Lettuce token 업그레이드 (Base58 8자 → 22자 이상 / UUID): 이 spec PR에 포함.

### 7. 테스트 계획 (v1 필수)

#### 7-1. 단위 테스트

| 테스트 | 검증 포인트 | 도구 |
|--------|------------|------|
| `SafeLeaderHistoryRecorder.recordAcquired` sink 예외 | warn log + null 반환, election 영향 없음 | MockK |
| `SafeLeaderHistoryRecorder.recordAcquired` CE rethrow | `assertFailsWith<CancellationException>` | JUnit 5 |
| `recordFailed` CE 전달 시 skip | warn log만, sink 미호출, IAE 전파 없음 | MockK |
| `sanitize()` errorMessage truncation | 512B 초과 → 잘림 (멀티바이트 경계 유지) | Kluent |
| `sanitize()` metadata 항목 제한 | 17개 → 16개 | Kluent |
| `sanitize()` metadata key sanitizeForLog | `\n` → `?` | Kluent |
| `truncateUtf8()` 멀티바이트 경계 | 한글 3바이트 문자 경계에서 잘리지 않음 | unit test |
| `LeaderLockHistoryRecord()` factory | blank lockName → `IllegalArgumentException` | assertFailsWith |
| `LeaderLockHistoryRecord()` metadata 방어 복사 | 원본 MutableMap 변경 후 record 영향 없음 | unit test |
| `LeaderHistoryKey` update 전략 | id != null → 첫 번째 전략 | unit test |
| `effectiveStatus()` ACQUIRED + 만료 | `lockedUntil < now` → EXPIRED 반환 | unit test |
| blocking recorder CE rethrow | `catch(Exception)` 앞 `catch(CancellationException) { throw e }` | assertFailsWith |

#### 7-2. 동시성 테스트

- **blocking sink**: `MultithreadingTester` (workers=8, rounds=50)로 `SafeLeaderHistoryRecorder.recordAcquired()` 동시 호출
- **suspend sink**: `SuspendedJobTester` 또는 `StructuredTaskScopeTester`
- 직접 `Thread` / `Executors` / `coroutineScope.launch` 사용 금지

#### 7-3. 통합 테스트 (Testcontainers)

| 모듈 | 인프라 | 검증 |
|------|--------|------|
| `leader-exposed-jdbc` | PostgreSQL | ACQUIRED→COMPLETED, ACQUIRED→FAILED, `id` 기반 update |
| `leader-exposed-r2dbc` | PostgreSQL (R2DBC) | 동일 |
| `leader-mongodb` | MongoDB | document 생성/update/TTL/`historyId` 기반 update |

#### 7-4. Sink 장애 경로

| 시나리오 | 기대 동작 |
|----------|-----------|
| sink DB 다운 시 acquire | election 성공, warn log, action 실행됨 |
| sink 응답 지연 (>1ms p99) | JMH 벤치마크로 측정, 초과 시 비동기 전환 검토 |
| sink recordAcquired null 반환 | recordCompleted/recordFailed skip, election 계속 |

#### 7-5. Schema migration 테스트 (PostgreSQL + MySQL V8+)

- 신규 컬럼 nullable DDL이 PostgreSQL 12+ 및 MySQL 8.0+에서 기존 데이터 손상 없이 적용되는지
  Testcontainers에서 검증한다.
- 롤백 스크립트(`ALTER TABLE DROP COLUMN`)를 동일 DB에서 실행하고 기존 레코드가 유지됨을
  검증한다.
- MySQL `ALGORITHM=INSTANT` DDL 검증 포함.
- **MySQL V8+는 이 spec PR에서 공식 지원 DB로 추가한다.** Testcontainers MySQL 의존성
  (`leader-exposed-jdbc`, `leader-exposed-r2dbc`)에 추가.

## Alternatives

### Option A: Core model + sink SPI, backend projections follow it

권장 옵션.

### Option B: Keep Exposed-only history

거부. semantic drift 위험.

### Option C: Full event-sourcing stream

v1 거부. volume과 query 설계 범위 과다.

## Risks

- `slot` type migration: Exposed integer `slot` vs core string `slotId`.
- enum 이전: `HistoryStatus` → `LeaderHistoryStatus`.
- Lettuce token 업그레이드: Base58 8자 → 22자. 기존 레코드의 token 포맷 변경.
- `lockedUntil` best-effort: watchdog 확장 이벤트를 추적하지 않으면 `lockedUntil < finishedAt` 가능.

## Acceptance criteria

- `LeaderHistoryKind` 미사용 — `LockIdentity.AnnotationKind` 직접 참조.
- `LeaderLockHistoryRecord` private constructor + `invoke` factory: `requireNotBlank` + `metadata.toMap()`.
- `LeaderLockHistoryRecord`와 `LeaderHistoryKey`가 `Serializable`이다.
- `LeaderHistoryKey`: `id: Long?` (DB PK) + `historyId: String?` (natural id) 분리.
- `SafeLeaderHistoryRecorder` (blocking + suspend): CE rethrow, truncation, `recordFailed` errorType/errorMessage 파생.
- `SafeLeaderHistoryRecorder`에 `MeterRegistry` 없음 — `leader-micrometer`의 `MicrometerSafeLeaderHistoryRecorder`가 분리 제공.
- `recordFailed` sink interface parameter: `errorType: String?`, `errorMessage: String?` (Throwable 아님).
- `truncateUtf8(maxBytes)` 유틸리티 — `bluetape4k-support` 또는 `leader-core internal`.
- Lettuce token ≥128 bit: Base58 22자 이상 또는 UUID 업그레이드 (이 PR 포함).
- Log injection 방지: `sanitizeForLog()` 함수로 CRLF + control chars 제거.
- Blocking recorder CE rethrow: `catch(CancellationException) { throw e }` 모든 메서드.
- Wiring 명세: elector constructor optional `SafeLeaderHistoryRecorder?` 파라미터.
- `effectiveStatus()` helper — `leader-core` top-level extension.
- Schema migration: nullable DDL + PostgreSQL lock_timeout + MySQL ALGORITHM=INSTANT + Flyway 경로.
- MySQL V8+ 공식 지원 DB 추가.
- MongoDB index health gauge (`leader.history.mongodb.index.state`).
- MongoDB TTL gauge live supplier.
- Exposed/MongoDB retention 기본값 차이 문서화.
- EXPIRED sweeper v1 out-of-scope 명시.
- `runIfLeader` throw 계약: lock 미획득/action 예외 → null 반환; CE → rethrow (유일한 예외).
- Lettuce 레거시 token(8자) 호환 정책: 기존 레코드 그대로 유지, 신규 acquire부터 128-bit.
- 테스트 계획 포함 (단위/동시성/통합/장애/DDL).
- Hot-path overhead p99 ≤ 1ms + JMH.
- English KDoc: `LeaderLockHistoryRecord`, `LeaderHistoryKey`, `LeaderHistorySink`,
  `SuspendLeaderHistorySink`, `SafeLeaderHistoryRecorder`, `SuspendSafeLeaderHistoryRecorder`,
  `MicrometerSafeLeaderHistoryRecorder`, `LeaderHistoryStatus`, `effectiveStatus()`, `truncateUtf8()`.
- README 갱신: `leader-core/README.md`, `leader-exposed-core/README.md`,
  `leader-mongodb/README.md`, `leader-micrometer/README.md`.

## Appendix — Review Iteration Log

### Round 1 (2026-05-13 Rev 1→2)

Phase 1: user/caller, developer, security, ops/SRE (4 × sonnet) + 6-tier advisor

| Tier | P0 | P1 | P2 |
|------|----|----|-----|
| 1 Security | 0 | 2 | 4 |
| 2 Ops/SRE | 0 | 4 | 4 |
| 3 Structural | 0 | 2 | 2 |
| 4 Kotlin | 0 | 2 | 2 |
| 5 Tests | 0 | 3 | 2 |
| 6 Perf | 0 | 2 | 2 |
| **합계** | **0** | **15** | **16** |

모두 Rev 2에 반영. Commit `e274ebe`.

### Round 2 (2026-05-13 Rev 2→3)

Phase 1 Round 2 (4 × sonnet) + Phase 3 Codex (Opus)

고유 P1 (중복 제거):

| # | 출처 | P1 |
|---|------|-----|
| 1 | caller | Wiring deferral 미해결 |
| 2 | caller | Natural-key predicate 모순 |
| 3 | caller+sec | `require()` → IAE 전파 audit-isolation 위반 |
| 4 | sec | Lettuce Base58 47-bit vs 128-bit 자기모순 |
| 5 | sec | Log injection — CRLF 미제거 |
| 6 | dev+codex | `truncateUtf8()` 미존재 |
| 7 | dev+codex | `init {}` metadata 스냅샷 주석만 |
| 8 | dev+ops | `MeterRegistry` leader-core 경계 위반 |
| 9 | codex | Blocking recorder CE 삼킴 |
| 10 | codex | `historyId: String?` vs Exposed Long PK |
| 11 | codex | `recordFailed` errorType/errorMessage 파생 미정의 |
| 12 | codex | `requireNotBlank` 누락 |
| 13 | ops | Schema migration DBA/CI 실행 불가 |
| 14 | ops | MongoDB index health probe 없음 |

모두 Rev 3에 반영. Commit (this PR).

### Phase 2 Critic (2026-05-13 Rev 3→4)

Phase 2 Opus critic: P1×2, P2×5, P3×3

| # | P | 찾은 문제 | 처리 |
|---|---|-----------|------|
| 1 | P1 | `runIfLeader` "절대 throw 않음" vs CE rethrow 정합성 | Rev 4 반영: 계약 명세 추가 |
| 2 | P1 | Lettuce 레거시 8자 token 호환 미정의 | Rev 4 반영: 레거시 정책 추가 |
| 3 | P2 | README/KDoc 요건 누락 | Rev 4 반영: acceptance criteria 추가 |
| 4 | P2 | Brainstorming depth 부족 | 기존 A/B/C 옵션으로 형식 충족; 추가 옵션은 plan 단계로 |
| 5 | P2 | `effectiveStatus()` DB 시간 vs 앱 시간 divergence | P3 수준으로 하향; JVM Instant.now()와 DB now()는 동일 서버에서 수ms 이내 |
| 6 | P2 | suspend wiring 예제 없음 | plan 단계에서 다룸 |
| 7 | P2 | `SuspendSafeLeaderHistoryRecorder` IO dispatcher 책임 | plan 단계에서 다룸 |

Rev 4 commit (pending).
