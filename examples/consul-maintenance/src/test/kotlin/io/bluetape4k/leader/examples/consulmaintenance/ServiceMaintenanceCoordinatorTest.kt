package io.bluetape4k.leader.examples.consulmaintenance

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.get
import io.bluetape4k.leader.consul.ConsulEndpoint
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.testcontainers.infra.ConsulServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceMaintenanceCoordinatorTest {

    companion object: KLogging() {
        private val consul: ConsulServer by lazy { ConsulServer.Launcher.consul }

        private fun randomName(): String = Base58.randomString(12)
    }

    @Test
    fun `only one service instance performs maintenance for the same Consul lock`() {
        val keyPrefix = MaintenanceKeyPrefix("bluetape4k/examples/consul-maintenance/test/${randomName()}")
        val lockName = MaintenanceLockName("service-maintenance:${randomName()}")

        val nodeA = coordinator(
            nodeId = "node-a",
            lockName = lockName,
            keyPrefix = keyPrefix,
            waitTime = 2.seconds,
        )
        val nodeB = coordinator(
            nodeId = "node-b",
            lockName = lockName,
            keyPrefix = keyPrefix,
            waitTime = 200.milliseconds,
        )
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val activeFuture = executor.submit<MaintenanceReport> {
                nodeA.performMaintenance {
                    started.countDown()
                    release.await(10.seconds)
                    listOf("drain-node-a")
                }
            }

            started.await(10.seconds).shouldBeTrue()

            val skipped = nodeB.performMaintenance { listOf("drain-node-b") }

            release.countDown()
            val active = activeFuture.get(10.seconds)
            val reacquired = nodeB.performMaintenance { listOf("drain-node-b") }

            log.debug { "active=$active" }
            active.status shouldBeEqualTo MaintenanceStatus.PERFORMED
            active.nodeId shouldBeEqualTo MaintenanceNodeId("node-a")
            active.completedSteps shouldBeEqualTo listOf("drain-node-a")

            log.debug { "skipped=$skipped" }
            skipped.status shouldBeEqualTo MaintenanceStatus.SKIPPED
            skipped.completedSteps shouldBeEqualTo emptyList()

            log.debug { "reacquired=$reacquired" }
            reacquired.status shouldBeEqualTo MaintenanceStatus.PERFORMED
            reacquired.nodeId shouldBeEqualTo MaintenanceNodeId("node-b")
            reacquired.completedSteps shouldBeEqualTo listOf("drain-node-b")
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun coordinator(
        nodeId: String,
        lockName: MaintenanceLockName,
        keyPrefix: MaintenanceKeyPrefix,
        waitTime: kotlin.time.Duration,
    ): ServiceMaintenanceCoordinator =
        ServiceMaintenanceCoordinator(
            config = ServiceMaintenanceConfig(
                nodeId = MaintenanceNodeId(nodeId),
                lockName = lockName,
                keyPrefix = keyPrefix,
                waitTime = waitTime,
                leaseTime = 10.seconds,
            ),
            endpoint = ConsulEndpoint(consul.url),
        )
}
