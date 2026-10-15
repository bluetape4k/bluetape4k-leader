package io.bluetape4k.leader.internal

/**
 * backend 예외를 재시도 가능성 또는 치명도에 따라 분류하는 확장 지점입니다.
 */
fun interface BackendErrorClassifier {

    /**
     * @param cause 분류할 backend 예외
     * @return 분류를 지원하지 않으면 `null`; 지원하면 오류 종류
     */
    fun classify(cause: Throwable): BackendErrorKind?
}

/**
 * backend 오류 처리에서 재시도와 중단 여부를 결정하는 오류 분류입니다.
 */
enum class BackendErrorKind {
    /**
     * 일시적인 연결 또는 backend 상태 오류로, 재시도 가능성이 있습니다.
     */
    TRANSIENT,

    /**
     * 요청이나 설정을 바꾸지 않으면 재시도해도 해결되지 않는 오류입니다.
     */
    NON_TRANSIENT,

    /**
     * 메모리 부족, stack overflow, linkage 오류처럼 실행을 계속하기 어려운 오류입니다.
     */
    FATAL,
}
