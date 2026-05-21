package io.bluetape4k.leader.examples.ratelimit

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import org.junit.jupiter.api.Test

class RateLimiterDemoTest: AbstractRateLimiterTest() {

    @Test
    fun `3 nodes dispatch once and consume external API below global quota`() {
        val report = RateLimiterDemo.runScenario(
            redisUrl = redis.url,
            quotaPerSecond = 10,
            windowSeconds = 3,
            attemptsPerSecond = 15,
        )

        report.scheduledNodeCount shouldBeEqualTo 1
        report.totalCalls shouldBeEqualTo report.consumedCalls
        report.totalCalls shouldBeLessOrEqualTo report.expectedMaxCalls
        report.workerReports.count { it.status == RateLimiterDemoStatus.REJECTED } shouldBeEqualTo 15
    }
}
