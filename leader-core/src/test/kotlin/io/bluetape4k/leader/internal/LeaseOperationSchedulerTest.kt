package io.bluetape4k.leader.internal

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.concurrent.await
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.seconds

class LeaseOperationSchedulerTest {

    companion object: KLogging()

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
                release.await(1.seconds)
            }.shouldNotBeNull()

            started.await(1.seconds)
            scheduler.submit { }.shouldNotBeNull()
            scheduler.submit { }.shouldBeNull()
            scheduler.queued shouldBeEqualTo 1

            release.countDown()
            scheduler.awaitIdle(2.seconds)
            scheduler.inFlight shouldBeEqualTo 0
            scheduler.queued shouldBeEqualTo 0
        }
    }
}
