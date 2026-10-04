package io.bluetape4k.leader.examples.prometheus

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test

class PrometheusScrapeContractTest {

    companion object: KLogging()

    @Test
    fun `non successful scrape reports status and body`() {
        val failure = assertFailsWith<AssertionError> {
            PrometheusScrapeResponse(
                statusCode = 503,
                body = "redis unavailable",
            ).requireSuccessful()
        }

        log.debug { "failure message=${failure.message}" }
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

        log.debug { "failure message=${failure.message}" }
        failure.message shouldContain "leader_aop_acquired_total"
        failure.message shouldContain "leader_history_sink_failures_total"
        failure.message shouldContain "leader_aop_attempts_total 0"
    }
}
