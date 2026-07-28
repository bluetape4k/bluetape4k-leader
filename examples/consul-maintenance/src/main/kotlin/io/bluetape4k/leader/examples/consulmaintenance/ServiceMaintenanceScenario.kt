package io.bluetape4k.leader.examples.consulmaintenance

import io.bluetape4k.leader.consul.ConsulEndpoint
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `ServiceMaintenanceScenario`는 example workflow에서 사용하는 설정, 상태, 또는 예제 workflow 값을 담는 모델입니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, route guard, metric, example intent를 문서화합니다.
 * @property endpoint example workflow 계약에서 `endpoint` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
internal class ServiceMaintenanceScenario(
    private val endpoint: ConsulEndpoint,
) {

    fun run(): List<MaintenanceReport> {
        val keyPrefix = MaintenanceKeyPrefix("bluetape4k/examples/consul-maintenance/demo")
        val lockName = MaintenanceLockName("service-maintenance:checkout")
        val nodeA = coordinator("checkout-a", lockName, keyPrefix, 2.seconds)
        val nodeB = coordinator("checkout-b", lockName, keyPrefix, 200.milliseconds)
        val nodeC = coordinator("checkout-c", lockName, keyPrefix, 200.milliseconds)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val activeFuture = executor.submit<MaintenanceReport> {
                nodeA.performMaintenance {
                    started.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    listOf("mark-instance-draining", "flush-inflight-requests", "rotate-service-endpoint")
                }
            }

            check(started.await(10, TimeUnit.SECONDS)) {
                "Timed out waiting for the first maintenance node to acquire leadership."
            }

            val skipped = listOf(
                nodeB.performMaintenance { listOf("checkout-b-should-not-run") },
                nodeC.performMaintenance { listOf("checkout-c-should-not-run") },
            )

            release.countDown()
            return listOf(activeFuture.get(10, TimeUnit.SECONDS)) + skipped
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
            endpoint = endpoint,
        )
}
