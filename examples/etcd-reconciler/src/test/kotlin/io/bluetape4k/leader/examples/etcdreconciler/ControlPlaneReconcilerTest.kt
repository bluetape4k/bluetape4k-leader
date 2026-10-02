package io.bluetape4k.leader.examples.etcdreconciler

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.awaitTermination
import io.bluetape4k.concurrent.get
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.testcontainers.infra.EtcdServer
import io.etcd.jetcd.Client
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ControlPlaneReconcilerTest {

    companion object: KLogging() {
        private val etcd: EtcdServer by lazy { EtcdServer.Launcher.etcd }

        private fun newClient(): Client =
            Client.builder()
                .endpoints(etcd.endpoint)
                .connectTimeout(10.seconds())
                .build()

        private fun randomName(): String = Base58.randomString(8)
    }

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
                        release.await(10.seconds)
                        listOf("deployment/api")
                    }
                }

                started.await(10.seconds).shouldBeTrue()

                val skipped = nodeB.reconcile { listOf("deployment/worker") }
                log.debug { "skipped=$skipped" }
                skipped.status shouldBeEqualTo ReconcileStatus.SKIPPED
                skipped.appliedResources shouldBeEqualTo emptyList()


                release.countDown()

                val active = activeFuture.get(10.seconds)
                log.debug { "active=$active" }
                active.status shouldBeEqualTo ReconcileStatus.APPLIED
                active.nodeId shouldBeEqualTo "node-a"
                active.appliedResources shouldBeEqualTo listOf("deployment/api")


                val reacquired = nodeB.reconcile { listOf("deployment/worker") }
                log.debug { "reacquired=$reacquired" }
                reacquired.status shouldBeEqualTo ReconcileStatus.APPLIED
                reacquired.nodeId shouldBeEqualTo "node-b"
                reacquired.appliedResources shouldBeEqualTo listOf("deployment/worker")
            } finally {
                release.countDown()
                executor.shutdown()
                executor.awaitTermination(5.seconds)
            }
        }
    }
}
