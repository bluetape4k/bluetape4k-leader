package io.bluetape4k.leader.micrometer

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.leader.local.LocalLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.utils.Runtimex
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.atomic.AtomicInteger

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MicrometerLeaderElectionListenerTest {

    companion object: KLogging()

    @Test
    fun `listener 이벤트를 Micrometer counter 로 기록한다`() {
        val registry = SimpleMeterRegistry()
        val listener = MicrometerLeaderElectionListener(registry, LeaderMetricTagOptions.Raw)
        val election = LocalLeaderElector().apply {
            addListener(listener)
        }

        election.runIfLeader("metrics-listener-job") { "done" } shouldBeEqualTo "done"

        eventCount("metrics-listener-job", "elected", registry) shouldBeEqualTo 1.0
        eventCount("metrics-listener-job", "revoked", registry) shouldBeEqualTo 1.0
        eventCount("metrics-listener-job", "skipped", registry) shouldBeEqualTo 0.0
    }

    @Test
    fun `listener default sanitizer collapses dynamic lock names`() {
        val registry = SimpleMeterRegistry()
        val listener = MicrometerLeaderElectionListener(registry)

        listener.onElected("tenant-a")
        listener.onElected("tenant-b")

        eventCount("redacted-lock", "elected", registry) shouldBeEqualTo 2.0
        eventCount("tenant-a", "elected", registry) shouldBeEqualTo 0.0
    }

    @Test
    fun `listener concurrent redacted first use creates one event counter`() {
        val registry = SimpleMeterRegistry()
        val listener = MicrometerLeaderElectionListener(registry)
        val sequence = AtomicInteger()

        MultithreadingTester()
            .workers(Runtimex.availableProcessors)
            .rounds(10)
            .add {
                listener.onElected("tenant-${sequence.incrementAndGet()}")
            }
            .run()

        eventCount("redacted-lock", "elected", registry) shouldBeEqualTo sequence.get().toDouble()
        eventCounters("redacted-lock", "elected", registry) shouldBeEqualTo 1
    }


    @Test
    fun `listener virtual thread redacted first use creates one event counter`() {
        val registry = SimpleMeterRegistry()
        val listener = MicrometerLeaderElectionListener(registry)
        val sequence = AtomicInteger()

        StructuredTaskScopeTester()
            .rounds(10 * Runtimex.availableProcessors)
            .add {
                listener.onElected("tenant-${sequence.incrementAndGet()}")
            }
            .run()

        eventCount("redacted-lock", "elected", registry) shouldBeEqualTo sequence.get().toDouble()
        eventCounters("redacted-lock", "elected", registry) shouldBeEqualTo 1
    }

    private fun eventCount(lockName: String, event: String, registry: SimpleMeterRegistry): Double =
        registry.find(MicrometerNames.METER_LEADER_EVENTS)
            .tag(MicrometerNames.TAG_LOCK_NAME, lockName)
            .tag(MicrometerNames.TAG_EVENT, event)
            .counter()
            ?.count() ?: 0.0

    private fun eventCounters(lockName: String, event: String, registry: SimpleMeterRegistry): Int =
        registry.find(MicrometerNames.METER_LEADER_EVENTS)
            .tag(MicrometerNames.TAG_LOCK_NAME, lockName)
            .tag(MicrometerNames.TAG_EVENT, event)
            .counters()
            .size
}
