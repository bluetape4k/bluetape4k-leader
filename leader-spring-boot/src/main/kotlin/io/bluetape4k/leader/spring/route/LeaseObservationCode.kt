package io.bluetape4k.leader.spring.route

/**
 * 요청별 lease 경계에서 사용할 수 있는 고정 관찰 코드입니다.
 *
 * 이 열거형은 외부 입력, lock 이름, 예외 유형을 포함하지 않습니다. 따라서 route
 * 응답·로그·metric·Actuator 표면에서 동적 cardinality가 만들어지지 않습니다.
 */
enum class LeaseObservationCode {
    CONTENTION,
    ADMISSION_REJECTED,
    UNSUPPORTED,
    SHUTDOWN,
    ORDINARY_FAILURE,
    TIMEOUT,
    STALE,
    BACKEND_ERROR,
    EXTEND_REJECTED,
    CLEANUP_TIMEOUT,
    DRAIN_TIMEOUT,
}
