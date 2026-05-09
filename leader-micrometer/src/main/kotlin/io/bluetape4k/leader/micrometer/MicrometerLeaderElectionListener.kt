package io.bluetape4k.leader.micrometer

import io.bluetape4k.leader.LeaderElectionListener
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * [LeaderElectionListener] 이벤트를 Micrometer counter로 기록하는 리스너입니다.
 *
 * ## 동작/계약
 * - `leader.election.events` counter를 `lock.name`, `event` 태그와 함께 기록합니다.
 * - `event` 값은 `elected`, `revoked`, `skipped`입니다.
 * - direct elector 데코레이터의 `shedlock.leader.*` 실행 메트릭과 별도로, listener 기반 lifecycle 이벤트만 기록합니다.
 *
 * ```kotlin
 * val listener = MicrometerLeaderElectionListener(registry)
 * val election = LocalLeaderElector().withListeners(listener)
 * election.runIfLeader("daily-job") { runJob() }
 * ```
 */
class MicrometerLeaderElectionListener(
    private val registry: MeterRegistry,
) : LeaderElectionListener {

    private val counters = ConcurrentHashMap<Pair<String, String>, Counter>()

    override fun onElected(lockName: String) {
        counter(lockName, EVENT_ELECTED).increment()
    }

    override fun onRevoked(lockName: String) {
        counter(lockName, EVENT_REVOKED).increment()
    }

    override fun onSkipped(lockName: String) {
        counter(lockName, EVENT_SKIPPED).increment()
    }

    private fun counter(lockName: String, event: String): Counter =
        counters.computeIfAbsent(lockName to event) { (name, value) ->
            Counter.builder(MicrometerNames.METER_LEADER_EVENTS)
                .tag(MicrometerNames.TAG_LOCK_NAME, name)
                .tag(MicrometerNames.TAG_EVENT, value)
                .register(registry)
        }

    private companion object {
        private const val EVENT_ELECTED = "elected"
        private const val EVENT_REVOKED = "revoked"
        private const val EVENT_SKIPPED = "skipped"
    }
}
