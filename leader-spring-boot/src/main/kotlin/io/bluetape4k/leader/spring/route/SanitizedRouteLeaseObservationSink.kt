package io.bluetape4k.leader.spring.route

/**
 * route lease가 외부 관찰 표면으로 이벤트를 전달하는 최소 경계입니다.
 *
 * 구현체는 고정된 [LeaseObservationCode]만 받아야 하며 lock 이름, token, leader
 * identity, backend URI, 원본 예외를 인자로 받지 않습니다.
 */
fun interface SanitizedRouteLeaseObservationSink {
    fun observe(code: LeaseObservationCode)
}

/** 관찰을 버리는 기본 sink입니다. Micrometer가 없는 환경의 bounded fallback입니다. */
object NoopRouteLeaseObservationSink : SanitizedRouteLeaseObservationSink {
    override fun observe(code: LeaseObservationCode) = Unit
}
