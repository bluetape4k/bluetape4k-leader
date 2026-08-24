package io.bluetape4k.leader.spring.route

import java.util.EnumMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 고정 크기 route lease 관찰 집계입니다.
 *
 * lock identity나 요청 수에 따라 map이 성장하지 않도록 모든 enum 값을 미리
 * 생성합니다. snapshot은 외부에서 수정할 수 없는 일반 map으로 반환됩니다.
 */
class LeaderRouteLeaseMetrics : SanitizedRouteLeaseObservationSink {
    private val counts = EnumMap<LeaseObservationCode, AtomicLong>(LeaseObservationCode::class.java).apply {
        LeaseObservationCode.entries.forEach { put(it, AtomicLong()) }
    }

    override fun observe(code: LeaseObservationCode) {
        counts[code]?.incrementAndGet()
    }

    fun count(code: LeaseObservationCode): Long = counts[code]?.get() ?: 0L

    fun snapshot(): Map<LeaseObservationCode, Long> =
        LeaseObservationCode.entries.associateWith { count(it) }

    fun reset() {
        counts.values.forEach { it.set(0L) }
    }
}
