package io.bluetape4k.leader.dynamodb.internal

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.leader.contract.AbstractMonotonicDeadlineMathContractTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class MonotonicDeadlineTest: AbstractMonotonicDeadlineMathContractTest() {

    override fun createDeadline(waitTime: Duration, ticker: () -> Long): DeadlineProbe {
        val deadline = MonotonicDeadline.fromNow(waitTime, ticker)
        return object: DeadlineProbe {
            override fun remainingNanos(): Long = deadline.remainingNanos()
            override fun remainingMillisForDelay(maxDelayMillis: Long): Long = deadline.remainingMillisForDelay(maxDelayMillis)
            override fun hasTimeRemaining(): Boolean = deadline.hasTimeRemaining()
        }
    }

    @Test
    fun `remainingMillisForDelay - max delay must be positive`() {
        val deadline = MonotonicDeadline.fromNow(1.milliseconds) { 42L }

        assertFailsWith<IllegalArgumentException> {
            deadline.remainingMillisForDelay(0L)
        }
    }
}
