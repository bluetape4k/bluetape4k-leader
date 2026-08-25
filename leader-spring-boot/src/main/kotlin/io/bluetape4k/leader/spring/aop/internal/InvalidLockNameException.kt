package io.bluetape4k.leader.spring.aop.internal

/**
 * Spring AOP backend 오류 처리에서 lock-name 계약 위반을 분리하는 내부 예외입니다.
 *
 * SpEL 평가 오류는 기존 failure mode를 유지하지만, backend에 전달할 이름의
 * 정책 위반은 `SKIP` 또는 `FAIL_OPEN_RUN`으로 흡수하지 않고 항상 전파합니다.
 */
internal class InvalidLockNameException(
    cause: IllegalArgumentException,
) : IllegalArgumentException(cause.message, cause)
