# Issue #50 leader history/audit common contract design

> Issue: #50 | Type: A Full Design, spec-only first | Date: 2026-05-12

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

## 권장 설계

### 1. Core model

`leader-core`에 아래 타입을 추가한다.

```kotlin
enum class LeaderHistoryStatus {
    ACQUIRED,
    COMPLETED,
    FAILED,
    EXPIRED,
}

enum class LeaderHistoryKind {
    SINGLE,
    GROUP,
}

data class LeaderLockHistoryRecord(
    val lockName: String,
    val kind: LeaderHistoryKind,
    val lockOwner: String? = null,
    val participantId: String? = null,
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
)
```

필드 의미:

- `lockName`: application-visible logical lock name.
- `kind`: single elector 또는 group elector 구분.
- `lockOwner`: backend/node owner. 현재 Exposed `lockOwner`에 대응한다.
- `participantId`: #72의 `leaderId` 같은 사용자 지정 group participant identity를
  담을 수 있는 확장 지점이다. v1에서는 nullable로 두고 기본 로직에 쓰지 않는다.
- `token`: acquire 시 생성된 fencing/ownership token. 완료/실패/만료 전환 시 대상
  검증에 사용한다.
- `slotId`: group lock의 slot/permit/member identity. 저장소별 native identity를
  문자열로 보존한다. 단일 리더는 null이다.
- `status`: `ACQUIRED -> COMPLETED | FAILED | EXPIRED` lifecycle.
- `startedAt`: acquire 성공 후 action 실행을 시작한 시각.
- `lockedUntil`: acquire 시점 기준 lease 만료 예정 시각. auto-extend 결과를 완전히
  추적하지 않는 best-effort expiry 기준이다.
- `finishedAt`, `durationMs`: `COMPLETED`/`FAILED` 전환 시 채운다.
- `errorType`, `errorMessage`: `FAILED` 전환 시 선택적으로 채운다. 너무 긴 message는
  backend adapter가 truncate한다.
- `metadata`: backend-specific diagnostic hints. public query contract에 의존하지
  않는 보조 정보로만 사용한다.

### 2. Sink SPI

`leader-core`에 blocking/suspend 겸용을 억지로 합치지 않는다. sync/async elector와
suspend elector의 호출 모델이 다르므로 기본 SPI는 blocking이고, suspend adapter는
별도 타입으로 둔다.

```kotlin
data class LeaderHistoryKey(
    val historyId: String?,
    val lockName: String,
    val token: String,
    val slotId: String? = null,
)

interface LeaderHistorySink {
    fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey?
    fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long)
    fun recordFailed(
        key: LeaderHistoryKey,
        finishedAt: Instant,
        durationMs: Long,
        error: Throwable? = null,
    )
}

interface SuspendLeaderHistorySink {
    suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey?
    suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long)
    suspend fun recordFailed(
        key: LeaderHistoryKey,
        finishedAt: Instant,
        durationMs: Long,
        error: Throwable? = null,
    )
}
```

추가 규칙:

- `NoopLeaderHistorySink`와 `NoopSuspendLeaderHistorySink`를 제공한다.
- elector는 sink를 직접 호출하지 않고 `SafeLeaderHistoryRecorder` 같은 작은 helper를
  통해 호출한다. helper는 sink 예외를 catch하고 warn log 후 무시한다.
- `recordAcquired()`가 실패하거나 `null`을 반환하면 완료/실패 전환도 건너뛴다.
- sink 구현은 id 기반 update가 가능하면 `historyId`를 반환하고, 그렇지 않으면
  `(lockName, token, slotId)`를 key로 삼아도 된다.

### 3. 상태 전환 계약

- `ACQUIRED`: lock/slot acquire가 성공하고 action 실행을 시작하기 직전에 기록한다.
- `COMPLETED`: action이 정상 반환한 경우 기록한다. `null` 반환도 정상 완료다.
- `FAILED`: action이 예외로 종료된 경우 기록한다.
- `CancellationException` 및 `CompletableFuture.cancel()`은 `FAILED`가 아니다.
  cancellation은 caller-controlled control flow이므로 v1에서는 terminal transition을
  기록하지 않는다. 기존 `ACQUIRED` 레코드는 expiry/sweeper가 처리한다.
- `EXPIRED`: process crash, cancellation skip, unlock 누락, lease expiry 등으로
  terminal event가 없는 `ACQUIRED` 레코드를 별도 sweeper가 `lockedUntil < now` 기준으로
  전환한다. elector hot path는 `EXPIRED`를 즉시 쓰지 않는다.

### 4. Exposed projection

기존 `LeaderLockHistoryTable`은 core model의 RDBMS projection으로 유지한다.

필요한 후속 변경:

- `leader-exposed-core`의 `HistoryStatus`는 `LeaderHistoryStatus`로 이전한다.
  호환성은 `typealias HistoryStatus = LeaderHistoryStatus` 또는 deprecation wrapper를
  검토한다.
- `slot` integer 컬럼은 기존 호환을 위해 유지하되 core field 이름은 `slotId`로 둔다.
  Exposed v1 adapter는 numeric slot을 `slot`에 쓰고, string `slotId` 컬럼 추가 여부는
  migration blast radius를 따로 검토한다.
- `errorType`, `errorMessage`, `kind`, `participantId`, `metadata` 컬럼은 후속 schema
  migration에서 추가한다. 기존 table과 호환되는 최소 구현은 기존 필드만 매핑한다.
- 전환 update 조건은 최소 `(id, token)`이고, group에서 string `slotId` 컬럼을 추가하면
  `(id, token, slotId)`까지 확장한다.
- retention은 기존처럼 `startedAt < cutoff` batch delete를 기본으로 한다.

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

Indexes:

- `{ lockName: 1, startedAt: -1 }`
- `{ token: 1 }`
- optional unique/partial update key `{ _id: 1, token: 1 }`
- retention TTL index on `startedAt` or `finishedAt` only when user explicitly configures
  retention. Do not reuse lock `expireAt` TTL semantics for history.

### 6. Redis/Hazelcast/ZooKeeper policy

- These backends do not need built-in persistent history for #50 DoD.
- They may accept a `LeaderHistorySink` option later and emit best-effort events into an
  externally supplied sink.
- If a backend-native audit transport is added, it must still use core
  `LeaderLockHistoryRecord` semantics.
- Volatile backend key expiry should not be treated as durable `EXPIRED` history unless a
  configured sink/sweeper observes and writes it.

## Alternatives

### Option A: Core model + sink SPI, backend projections follow it

This is the recommended option. It fixes semantic drift before MongoDB and other backends add
audit support, while keeping backend implementation as follow-up work.

### Option B: Keep Exposed-only history

Rejected. It preserves current behavior but leaves MongoDB and future backends to invent their
own audit vocabulary, which is exactly the risk #50 was opened to avoid.

### Option C: Full event-sourcing stream

Rejected for v1. Recording skipped attempts, retries, extension events, heartbeats, and release
events would be more complete, but it increases volume and query design scope. The current
product need is acquisition lifecycle audit, not a full telemetry stream.

## Risks

- `slot` type migration: Exposed has integer `slot`, while core needs portable string
  `slotId`. Avoid schema churn in the spec-only PR and plan a compatibility migration.
- enum relocation: moving `HistoryStatus` from Exposed to core can break imports. Keep a
  compatibility alias/deprecation path.
- cancellation semantics: leaving `ACQUIRED` until expiry is consistent with existing lessons,
  but users may expect a terminal `CANCELLED`. Defer `CANCELLED` unless a concrete product need
  appears.
- auto-extend drift: `lockedUntil` at acquire time may become stale when watchdog extends the
  lock. Treat it as best-effort expiry evidence unless future events track extension updates.
- error field storage: unbounded exception messages can leak sensitive data. Truncate and avoid
  stack traces in default sinks.

## Acceptance criteria

- #50 and #72 relationship is documented as independent, with a naming guard for future
  `leaderId`.
- A core history record/status/sink contract is specified.
- Exposed projection and migration concerns are specified.
- MongoDB collection/index/retention semantics are specified.
- Redis/Hazelcast/ZooKeeper optional/best-effort policy is specified.
- Audit failure policy is explicit: audit must not change election/action behavior.
- Implementation is split into follow-up tasks; no backend implementation is required in this
  spec-only pass.
