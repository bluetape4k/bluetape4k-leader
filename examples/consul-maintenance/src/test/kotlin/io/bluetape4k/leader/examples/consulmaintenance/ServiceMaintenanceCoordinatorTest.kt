package io.bluetape4k.leader.examples.consulmaintenance

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.consul.ConsulEndpoint
import io.bluetape4k.testcontainers.infra.ConsulServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceMaintenanceCoordinatorTest {

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
                    release.await(10, TimeUnit.SECONDS)
                    listOf("drain-node-a")
                }
            }

            started.await(10, TimeUnit.SECONDS) shouldBeEqualTo true
            val skipped = nodeB.performMaintenance { listOf("drain-node-b") }

            release.countDown()
            val active = activeFuture.get(10, TimeUnit.SECONDS)
            val reacquired = nodeB.performMaintenance { listOf("drain-node-b") }

            active.status shouldBeEqualTo MaintenanceStatus.PERFORMED
            active.nodeId shouldBeEqualTo MaintenanceNodeId("node-a")
            active.completedSteps shouldBeEqualTo listOf("drain-node-a")
            skipped.status shouldBeEqualTo MaintenanceStatus.SKIPPED
            skipped.completedSteps shouldBeEqualTo emptyList()
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

    private fun randomName(): String = Base58.randomString(8)

    companion object {
        private val consul: ConsulServer = ConsulServer.Launcher.consul
    }
}
