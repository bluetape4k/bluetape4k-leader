package io.bluetape4k.leader.internal

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class LeaseOperationSchedulerTest {

    @Test
    fun `queue admission is bounded and task counters return to baseline`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        LeaseOperationScheduler(
            maxInFlight = 1,
            queueCapacity = 1,
            threadNamePrefix = "test-lease",
        ).use { scheduler ->
            scheduler.submit {
                started.countDown()
                release.await(1, TimeUnit.SECONDS)
            }.shouldNotBeNull()
            started.await(1, TimeUnit.SECONDS)
            scheduler.submit { Unit }.shouldNotBeNull()
            scheduler.submit { Unit }.shouldBeNull()
            scheduler.queued shouldBeEqualTo 1
            release.countDown()
            scheduler.awaitIdle(2.seconds)
            scheduler.inFlight shouldBeEqualTo 0
            scheduler.queued shouldBeEqualTo 0
        }
    }
}
