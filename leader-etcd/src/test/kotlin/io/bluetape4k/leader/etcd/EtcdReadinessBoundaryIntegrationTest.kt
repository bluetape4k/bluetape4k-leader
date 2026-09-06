package io.bluetape4k.leader.etcd

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.leader.testcontainers.ReadinessEndpoint
import io.bluetape4k.leader.testcontainers.readinessBoundaryWaitStrategy
import io.bluetape4k.testcontainers.infra.EtcdServer
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.testcontainers.containers.ContainerLaunchException
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.wait.strategy.WaitStrategy
import org.testcontainers.containers.wait.strategy.WaitStrategyTarget
import java.time.Duration

@Tag("integration")
@Execution(ExecutionMode.SAME_THREAD)
class EtcdReadinessBoundaryIntegrationTest {

    @Test
    fun `host wait 실패 직전에 실제 세 경계의 정상 증거를 수집한다`() {
        val etcd = EtcdServer(reuse = false).apply {
            waitingFor(
                readinessBoundaryWaitStrategy(
                    endpoint = ETCD_READINESS_ENDPOINT,
                    delegate = ReadyThenFailWaitStrategy(ETCD_READINESS_ENDPOINT),
                ),
            )
        }

        val thrown = assertFailsWith<ContainerLaunchException> {
            etcd.start()
        }
        val diagnostic = thrown.causes()
            .mapNotNull { it.message }
            .first { "Readiness boundary diagnostic:" in it }

        diagnostic shouldContain "boundary=UNKNOWN"
        diagnostic shouldContain "internal=SUCCESS"
        diagnostic shouldContain "host=SUCCESS"
        diagnostic shouldContain "mapping=SUCCESS"
    }

    private class ReadyThenFailWaitStrategy(
        endpoint: ReadinessEndpoint,
    ): WaitStrategy {
        private val readiness = Wait.forHttp(endpoint.path)
            .forPort(endpoint.containerPort)
            .forStatusCode(200)

        override fun waitUntilReady(waitStrategyTarget: WaitStrategyTarget) {
            readiness.waitUntilReady(waitStrategyTarget)
            throw ContainerLaunchException("synthetic host wait failure after readiness")
        }

        override fun withStartupTimeout(startupTimeout: Duration): WaitStrategy {
            readiness.withStartupTimeout(startupTimeout)
            return this
        }
    }

    private fun Throwable.causes(): Sequence<Throwable> =
        generateSequence(this) { it.cause }

    private companion object {
        val ETCD_READINESS_ENDPOINT =
            ReadinessEndpoint(EtcdServer.NAME, EtcdServer.CLIENT_PORT, "/health")
    }
}
