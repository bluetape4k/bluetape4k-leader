package io.bluetape4k.leader.examples.prometheus

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldContain
import org.junit.jupiter.api.Test

class PrometheusScrapeContractTest {

    @Test
    fun `non successful scrape reports status and body`() {
        val failure = assertFailsWith<AssertionError> {
            PrometheusScrapeResponse(
                statusCode = 503,
                body = "redis unavailable",
            ).requireSuccessful()
        }

        failure.message shouldContain "status=503"
        failure.message shouldContain "body=redis unavailable"
    }

    @Test
    fun `missing metric diagnostic reports names and scrape body`() {
        val failure = assertFailsWith<AssertionError> {
            "leader_aop_attempts_total 0".requireMetrics(
                listOf("leader_aop_acquired_total", "leader_history_sink_failures_total"),
            )
        }

        failure.message shouldContain "leader_aop_acquired_total"
        failure.message shouldContain "leader_history_sink_failures_total"
        failure.message shouldContain "leader_aop_attempts_total 0"
    }
}
