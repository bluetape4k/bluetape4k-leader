package io.bluetape4k.leader.internal

/**
 * SPI for classifying backend exceptions.
 *
 * **`leader-core` must not directly reference backend exception classes** (R5-F4 dependency inversion).
 * Each backend module implements a classifier for its own backend and the elector composes them.
 *
 * ## Behavior / Contract
 * - Returning `null` from `classify` means "unclassifiable" — delegates to the next classifier in the chain.
 * - [CompositeBackendErrorClassifier] chains backend-specific classifiers with [CoreBackendErrorClassifier].
 * - The final fallback is [BackendErrorKind.NON_TRANSIENT] (safe default).
 *
 * ## Example
 * ```kotlin
 * // leader-redis-lettuce/.../internal/LettuceBackendErrorClassifier.kt
 * internal object LettuceBackendErrorClassifier : BackendErrorClassifier {
 *     override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
 *         is io.lettuce.core.RedisCommandTimeoutException -> BackendErrorKind.TRANSIENT
 *         is io.lettuce.core.RedisConnectionException -> BackendErrorKind.TRANSIENT
 *         is io.lettuce.core.RedisCommandExecutionException -> BackendErrorKind.NON_TRANSIENT
 *         else -> null  // unclassifiable — chain next
 *     }
 * }
 * ```
 */
fun interface BackendErrorClassifier {

    /**
     * Classifies [cause].
     *
     * @return classification result; `null` means this classifier cannot classify — delegates to the next in chain
     */
    fun classify(cause: Throwable): BackendErrorKind?
}

/**
 * Classification result for backend errors.
 *
 * ## Classification Policy
 * - [TRANSIENT] — retryable; watchdog/caller logs WARN + metric and continues.
 * - [NON_TRANSIENT] — permanent error; caller handles explicitly (throw or stop).
 * - [FATAL] — fatal JVM error ([OutOfMemoryError], [StackOverflowError], [LinkageError]).
 *   Must not be wrapped or caught — leave to the JVM termination path.
 */
enum class BackendErrorKind {
    /** Retryable — log WARN + metric and continue. */
    TRANSIENT,

    /** Permanent error — throw or stop; caller decides. */
    NON_TRANSIENT,

    /**
     * Fatal JVM error ([OutOfMemoryError], [StackOverflowError], [LinkageError]).
     *
     * [io.bluetape4k.leader.ExtendOutcome.BackendError] accepts only [Exception],
     * so FATAL [Error] must not be wrapped — propagate directly.
     */
    FATAL,
}
