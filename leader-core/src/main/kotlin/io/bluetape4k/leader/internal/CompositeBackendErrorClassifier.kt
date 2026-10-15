package io.bluetape4k.leader.internal

/**
 * backend 전용 분류를 먼저 적용하고 core 분류로 보완하는 오류 분류기입니다.
 * 두 분류기가 모두 알 수 없는 예외는 `NON_TRANSIENT`로 처리합니다.
 * @property backendSpecific 현재 backend 예외 유형을 분류하는 우선 적용 classifier
 */
class CompositeBackendErrorClassifier(
    private val backendSpecific: BackendErrorClassifier,
): BackendErrorClassifier {

    /**
     * @param cause 분류할 backend 예외
     * @return backend 분류, core 분류 순으로 적용한 결과. 두 분류기가 모르면 `NON_TRANSIENT`
     */
    override fun classify(cause: Throwable): BackendErrorKind =
        backendSpecific.classify(cause)
            ?: CoreBackendErrorClassifier.classify(cause)
            ?: BackendErrorKind.NON_TRANSIENT
}
