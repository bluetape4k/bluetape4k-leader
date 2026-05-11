package io.bluetape4k.leader

import java.time.Instant

/**
 * [LeaderLockHandle.Real.extend] / [LeaderLockHandle.Real.extendSuspend] 의 상세 결과.
 *
 * `Boolean` API ([LockExtender.extendActiveLock]) 는 `is Extended` 변환과 동치이며, 운영 가시성이
 * 필요한 경우 [LockExtender.extendActiveLockDetailed] 를 사용해 분류된 결과를 받습니다.
 *
 * ## 분류
 * - [Extended] — backend extend 성공. `observedExpireAt` 은 best-effort 값 (backend 별 정확도 상이)
 * - [NotHeld] — token mismatch / lease 만료 / takeover 발생 — 더 이상 lock 미보유
 * - [WrongThread] — Redisson 의 thread-bound 락에서 acquire 와 다른 thread 에서 호출
 * - [BackendError] — transient (재시도 가능) 또는 non-transient backend 오류. `cause` 는 [Exception] 만 허용 (FATAL [Error] 차단)
 *
 * ## Boolean 변환 정책
 * `LockExtender.extendActiveLock(d)` 가 반환하는 `Boolean` 은:
 * - [Extended] → `true`
 * - [NotHeld], [WrongThread] → `false` (WARN log + metric)
 * - [BackendError] (transient) → `false` (WARN log + metric)
 * - [BackendError] (non-transient) → throw (caller 책임)
 *
 * ## Example
 * ```kotlin
 * when (val outcome = LockExtender.extendActiveLockDetailed(60.seconds)) {
 *     is ExtendOutcome.Extended -> log.info { "lease extended until ${outcome.observedExpireAt}" }
 *     is ExtendOutcome.NotHeld -> rollbackWork()
 *     is ExtendOutcome.WrongThread -> log.warn { "Redisson thread-bound — dispatched from wrong thread" }
 *     is ExtendOutcome.BackendError -> retry(outcome.cause)
 * }
 * ```
 */
sealed interface ExtendOutcome {

    /**
     * extend 성공.
     *
     * @property observedExpireAt **best-effort** new expire time. backend 별 정확도:
     * - Lettuce / Hazelcast / Local: server-side 시각 사용 → 정확
     * - Redisson: Redisson 내부 atomic — client clock 사용 가능 → ±50ms
     * - MongoDB: server-side `$$NOW` aggregation — 정확
     * - Exposed JDBC/R2DBC: DB server 시각 (`now()` SQL) — 정확
     * - ZooKeeper: TTL 개념 없음 — `Instant.MAX` (session-held liveness passthrough)
     *
     * caller 는 정확한 deadline 으로 사용 X — observability/logging 용.
     */
    data class Extended(val observedExpireAt: Instant) : ExtendOutcome

    /** token mismatch / lease 만료 / takeover — 더 이상 lock 미보유. */
    data object NotHeld : ExtendOutcome

    /** Redisson 의 thread-bound 락에서 acquire 와 다른 thread 에서 호출. */
    data object WrongThread : ExtendOutcome

    /**
     * Backend 오류. transient (재시도 가능) / non-transient 분류는 `BackendErrorClassifier` 사용.
     *
     * `cause` 는 [Exception] 만 허용 — FATAL [Error] ([OutOfMemoryError], [StackOverflowError],
     * [LinkageError] 등) 은 wrap 금지 (propagate).
     */
    data class BackendError(val cause: Exception) : ExtendOutcome

    /** Boolean API 변환용 short-cut. */
    val isExtended: Boolean get() = this is Extended
}
