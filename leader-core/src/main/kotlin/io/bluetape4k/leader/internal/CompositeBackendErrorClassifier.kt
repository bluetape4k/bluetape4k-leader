package io.bluetape4k.leader.internal

/**
 * Composite that chains a backend-specific classifier with [CoreBackendErrorClassifier].
 *
 * ## Behavior / Contract
 * Classification order:
 * 1. Attempt `backendSpecific.classify(cause)`
 * 2. If `null` returned, attempt `[CoreBackendErrorClassifier].classify(cause)`
 * 3. If both return `null`, fall back to [BackendErrorKind.NON_TRANSIENT] (safe default — caller handles explicitly)
 *
 * ## Example
 * ```kotlin
 * // in a backend module:
 * val classifier = CompositeBackendErrorClassifier(LettuceBackendErrorClassifier)
 *
 * when (classifier.classify(ex)) {
 *     BackendErrorKind.TRANSIENT     -> { log.warn { "transient backend error — continuing" } }
 *     BackendErrorKind.NON_TRANSIENT -> throw ex
 *     BackendErrorKind.FATAL         -> throw ex  // FATAL Error — propagate
 * }
 * ```
 *
 * @param backendSpecific backend-specific [BackendErrorClassifier] provided by the backend module
 */
class CompositeBackendErrorClassifier(
    private val backendSpecific: BackendErrorClassifier,
) : BackendErrorClassifier {

    /**
     * Classifies [cause]. Never returns `null` — fallback is [BackendErrorKind.NON_TRANSIENT].
     */
    override fun classify(cause: Throwable): BackendErrorKind =
        backendSpecific.classify(cause)
            ?: CoreBackendErrorClassifier.classify(cause)
            ?: BackendErrorKind.NON_TRANSIENT
}
