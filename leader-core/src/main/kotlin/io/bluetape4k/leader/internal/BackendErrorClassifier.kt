package io.bluetape4k.leader.internal

/**
 * Backend exception 분류 SPI.
 *
 * **`leader-core` 는 backend exception class 직접 참조 금지** (R5-F4 의존성 역전).
 * 각 backend module 이 자기 backend 용 classifier 구현 + elector 가 합성하여 사용.
 *
 * ## 동작/계약
 * - `classify` 는 `null` 반환 시 "분류 불가" 를 의미 — chain 의 다음 classifier 에 위임.
 * - [CompositeBackendErrorClassifier] 가 backend-specific 과 [CoreBackendErrorClassifier] 를 chain.
 * - 최종 fallback 은 [BackendErrorKind.NON_TRANSIENT] (safe default).
 *
 * ## Example
 * ```kotlin
 * // leader-redis-lettuce/.../internal/LettuceBackendErrorClassifier.kt
 * internal object LettuceBackendErrorClassifier : BackendErrorClassifier {
 *     override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
 *         is io.lettuce.core.RedisCommandTimeoutException -> BackendErrorKind.TRANSIENT
 *         is io.lettuce.core.RedisConnectionException -> BackendErrorKind.TRANSIENT
 *         is io.lettuce.core.RedisCommandExecutionException -> BackendErrorKind.NON_TRANSIENT
 *         else -> null  // 분류 불가 — chain next
 *     }
 * }
 * ```
 */
fun interface BackendErrorClassifier {

    /**
     * [cause] 를 분류합니다.
     *
     * @return 분류 결과. `null` = 이 classifier 가 분류 불가 — chain 다음 classifier 에 위임
     */
    fun classify(cause: Throwable): BackendErrorKind?
}

/**
 * Backend error 분류 결과.
 *
 * ## 분류 정책
 * - [TRANSIENT] — 재시도 가능. watchdog/caller 가 WARN log + metric 후 계속.
 * - [NON_TRANSIENT] — 영구 오류. caller 가 명시적으로 처리 (throw 또는 stop).
 * - [FATAL] — JVM 치명적 오류 ([OutOfMemoryError], [StackOverflowError], [LinkageError]).
 *   wrap 또는 catch 금지 — JVM 종료 경로에 맡김.
 */
enum class BackendErrorKind {
    /** 재시도 가능 — WARN log + metric 후 계속. */
    TRANSIENT,

    /** 영구 오류 — throw 또는 중단. caller 가 결정. */
    NON_TRANSIENT,

    /**
     * JVM 치명적 오류 ([OutOfMemoryError], [StackOverflowError], [LinkageError]).
     *
     * [io.bluetape4k.leader.ExtendOutcome.BackendError] 는 [Exception] 만 허용하므로
     * FATAL [Error] 는 wrap 금지 — propagate.
     */
    FATAL,
}
