package io.bluetape4k.leader.internal

/**
 * Backend-specific classifier 와 [CoreBackendErrorClassifier] 를 chain 한 composite.
 *
 * ## 동작/계약
 * 분류 순서:
 * 1. `backendSpecific.classify(cause)` 시도
 * 2. `null` 반환 시 [CoreBackendErrorClassifier].classify(cause)` 시도
 * 3. 둘 다 `null` 이면 [BackendErrorKind.NON_TRANSIENT] (safe default — caller 가 명시 처리)
 *
 * ## Example
 * ```kotlin
 * // backend module 에서:
 * val classifier = CompositeBackendErrorClassifier(LettuceBackendErrorClassifier)
 *
 * when (classifier.classify(ex)) {
 *     BackendErrorKind.TRANSIENT     -> { log.warn { "transient backend error — continuing" } }
 *     BackendErrorKind.NON_TRANSIENT -> throw ex
 *     BackendErrorKind.FATAL         -> throw ex  // FATAL Error — propagate
 * }
 * ```
 *
 * @param backendSpecific backend 모듈이 제공하는 backend-specific [BackendErrorClassifier]
 */
class CompositeBackendErrorClassifier(
    private val backendSpecific: BackendErrorClassifier,
) : BackendErrorClassifier {

    /**
     * [cause] 를 분류합니다. 절대 `null` 을 반환하지 않음 — fallback 은 [BackendErrorKind.NON_TRANSIENT].
     */
    override fun classify(cause: Throwable): BackendErrorKind =
        backendSpecific.classify(cause)
            ?: CoreBackendErrorClassifier.classify(cause)
            ?: BackendErrorKind.NON_TRANSIENT
}
