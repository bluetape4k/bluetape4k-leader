package io.bluetape4k.leader.micrometer

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.local.LocalLeaderElector
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MicrometerLeaderElectionListenerTest {

    @Test
    fun `listener 이벤트를 Micrometer counter 로 기록한다`() {
        val registry = SimpleMeterRegistry()
        val listener = MicrometerLeaderElectionListener(registry)
        val election = LocalLeaderElector().apply {
            addListener(listener)
        }

        election.runIfLeader("metrics-listener-job") { "done" }

        eventCount("metrics-listener-job", "elected", registry) shouldBeEqualTo 1.0
        eventCount("metrics-listener-job", "revoked", registry) shouldBeEqualTo 1.0
        eventCount("metrics-listener-job", "skipped", registry) shouldBeEqualTo 0.0
    }

    private fun eventCount(lockName: String, event: String, registry: SimpleMeterRegistry): Double =
        registry.find(MicrometerNames.METER_LEADER_EVENTS)
            .tag(MicrometerNames.TAG_LOCK_NAME, lockName)
            .tag(MicrometerNames.TAG_EVENT, event)
            .counter()
            ?.count() ?: 0.0
}
