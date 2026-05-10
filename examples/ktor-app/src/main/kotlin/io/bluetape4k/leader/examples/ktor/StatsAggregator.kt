package io.bluetape4k.leader.examples.ktor

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 시간별 통계 집계 작업의 도메인 객체.
 *
 * ## 동작/계약
 *
 * - 다중 인스턴스 환경에서 [LeaderElectionPlugin] + [Application.leaderScheduled] 와 결합되어
 *   매 cycle 마다 단 하나의 인스턴스만 [aggregate] 를 호출한다.
 * - in-memory `runCount` + `lastRunAt` 만 보유하는 단순 데모 — 프로덕션에서는 Redis/DB 등 외부 저장소로 대체.
 * - `aggregate` 는 멱등성을 보장하지 않는다 — 호출 횟수만큼 카운터가 증가한다.
 * - `currentState` 는 thread-safe 하다 — `AtomicLong` + `AtomicReference` 로 보호된다.
 *
 * ```kotlin
 * val aggregator = StatsAggregator()
 * aggregator.aggregate()
 * val state = aggregator.currentState()  // runCount=1, lastRunAt=...
 * ```
 *
 * @see StatsAggregatorState
 */
class StatsAggregator {

    companion object: KLogging()

    private val runCount = AtomicLong(0L)
    private val lastRunAt = AtomicReference<Instant?>(null)

    /**
     * 한 cycle 의 집계 작업을 수행한다.
     *
     * - `runCount` 를 1 증가시키고 `lastRunAt` 을 현재 시각으로 갱신한다.
     * - 데모 목적이므로 실제 집계 로직은 생략 — 카운터 갱신 + INFO 로그만 남긴다.
     * - suspend 함수로 정의되어 [Application.leaderScheduled] 와 자연스럽게 결합된다.
     */
    suspend fun aggregate() {
        val now = Instant.now()
        val current = runCount.incrementAndGet()
        lastRunAt.set(now)
        log.info { "시간별 통계 집계 cycle #$current 실행 (lastRunAt=$now)" }
    }

    /**
     * 현재 집계 상태의 스냅샷을 반환한다.
     *
     * `runCount` 와 `lastRunAt` 은 같은 cycle 의 값이 보장되지 않는다 (CAS 분리). 데모 목적상 허용한다.
     */
    fun currentState(): StatsAggregatorState =
        StatsAggregatorState(runCount = runCount.get(), lastRunAt = lastRunAt.get())
}

/**
 * REST API 응답에 노출되는 [StatsAggregator] 의 상태 스냅샷.
 *
 * @property runCount 누적 집계 cycle 실행 횟수
 * @property lastRunAt 마지막 cycle 실행 시각 (UTC). 한 번도 실행되지 않았다면 `null`.
 */
data class StatsAggregatorState(
    val runCount: Long,
    val lastRunAt: Instant?,
)
