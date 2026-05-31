package io.bluetape4k.leader.examples.etcdreconciler

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.codec.Base58
import io.bluetape4k.testcontainers.infra.EtcdServer
import io.etcd.jetcd.Client
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ControlPlaneReconcilerTest {

    @Test
    fun `only one control-plane node reconciles for the same lock`() {
        newClient().use { client ->
            val keyPrefix = "/bluetape4k/examples/etcd-reconciler/test/${randomName()}"
            val lockName = "control-plane:${randomName()}"
            val nodeA = ControlPlaneReconciler(
                nodeId = "node-a",
                client = client,
                lockName = lockName,
                keyPrefix = keyPrefix,
                waitTime = 2.seconds,
                leaseTime = 10.seconds,
            )
            val nodeB = ControlPlaneReconciler(
                nodeId = "node-b",
                client = client,
                lockName = lockName,
                keyPrefix = keyPrefix,
                waitTime = 200.milliseconds,
                leaseTime = 10.seconds,
            )
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()

            try {
                val activeFuture = executor.submit<ReconcileReport> {
                    nodeA.reconcile {
                        started.countDown()
                        release.await(10, TimeUnit.SECONDS)
                        listOf("deployment/api")
                    }
                }

                started.await(10, TimeUnit.SECONDS) shouldBeEqualTo true
                val skipped = nodeB.reconcile { listOf("deployment/worker") }

                release.countDown()
                val active = activeFuture.get(10, TimeUnit.SECONDS)
                val reacquired = nodeB.reconcile { listOf("deployment/worker") }

                active.status shouldBeEqualTo ReconcileStatus.APPLIED
                active.nodeId shouldBeEqualTo "node-a"
                active.appliedResources shouldBeEqualTo listOf("deployment/api")
                skipped.status shouldBeEqualTo ReconcileStatus.SKIPPED
                skipped.appliedResources shouldBeEqualTo emptyList()
                reacquired.status shouldBeEqualTo ReconcileStatus.APPLIED
                reacquired.nodeId shouldBeEqualTo "node-b"
                reacquired.appliedResources shouldBeEqualTo listOf("deployment/worker")
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    private fun newClient(): Client =
        Client.builder()
            .endpoints(etcd.endpoint)
            .connectTimeout(Duration.ofSeconds(10))
            .build()

    private fun randomName(): String = Base58.randomString(8)

    companion object {
        private val etcd: EtcdServer = EtcdServer.Launcher.etcd
    }
}
