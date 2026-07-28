package io.bluetape4k.leader.examples.ktor

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * `StatsAggregator`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 */
class StatsAggregator {

    companion object: KLogging()

    private val runCount = AtomicLong(0L)
    private val lastRunAt = AtomicReference<Instant?>(null)

    /**
     * `aggregate` 호출은 example workflow 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    suspend fun aggregate() {
        val now = Instant.now()
        val current = runCount.incrementAndGet()
        lastRunAt.set(now)
        log.info { "시간별 통계 집계 cycle #$current 실행 (lastRunAt=$now)" }
    }

    /**
     * `currentState` 호출은 example workflow 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun currentState(): StatsAggregatorState =
        StatsAggregatorState(runCount = runCount.get(), lastRunAt = lastRunAt.get())
}

/**
 * `StatsAggregatorState`는 example workflow에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property runCount example workflow 계약에서 `runCount` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property lastRunAt example workflow 계약에서 `lastRunAt` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class StatsAggregatorState(
    val runCount: Long,
    val lastRunAt: Instant?,
)
