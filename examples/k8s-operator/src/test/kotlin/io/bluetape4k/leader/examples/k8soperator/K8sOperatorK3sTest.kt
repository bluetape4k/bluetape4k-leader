package io.bluetape4k.leader.examples.k8soperator

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.k8s.KubernetesLeaseLeaderElector
import io.bluetape4k.leader.k8s.KubernetesLeaseOptions
import io.bluetape4k.testcontainers.infra.K3sServer
import io.fabric8.kubernetes.client.KubernetesClient
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Tag("k8s")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class K8sOperatorK3sTest {

    @Test
    fun `only one operator replica reconciles while lease is held`() {
        k3s.kubernetesClient().use { client ->
            val lockName = "operator-${io.bluetape4k.codec.Base58.randomString(8).lowercase()}"
            withCleanLease(client, lockName) {
                val workload = DemoCustomResourceWorkload()
                val holder = elector(client, "operator-0")
                val standby = OperatorController(
                    leaderElector = elector(client, "operator-1"),
                    workload = workload,
                    lockName = lockName,
                    podName = "operator-1",
                )

                val observedDuringHold = holder.runIfLeader(lockName) {
                    standby.reconcileTick()
                    workload.reconciliationCount()
                }

                observedDuringHold shouldBeEqualTo 0L
                standby.tickCount() shouldBeEqualTo 1L
                workload.reconciliationCount() shouldBeEqualTo 0L
            }
        }
    }

    @Test
    fun `operator failover reconciles after prior holder releases`() {
        k3s.kubernetesClient().use { client ->
            val lockName = "operator-${io.bluetape4k.codec.Base58.randomString(8).lowercase()}"
            withCleanLease(client, lockName) {
                val workload = DemoCustomResourceWorkload()
                val first = OperatorController(
                    leaderElector = elector(client, "operator-0"),
                    workload = workload,
                    lockName = lockName,
                    podName = "operator-0",
                )
                val second = OperatorController(
                    leaderElector = elector(client, "operator-1"),
                    workload = workload,
                    lockName = lockName,
                    podName = "operator-1",
                )

                first.reconcileTick()
                second.reconcileTick()

                workload.reconciliationCount() shouldBeEqualTo 2L
            }
        }
    }

    private fun elector(
        client: KubernetesClient,
        nodeId: String,
    ): KubernetesLeaseLeaderElector =
        KubernetesLeaseLeaderElector(
            client,
            KubernetesLeaseOptions(
                namespace = NAMESPACE,
                retryDelay = 10.milliseconds,
                leaderOptions = LeaderElectionOptions(
                    waitTime = 150.milliseconds,
                    leaseTime = 1.seconds,
                    nodeId = nodeId,
                ),
            ),
        )

    private inline fun withCleanLease(
        client: KubernetesClient,
        lockName: String,
        block: () -> Unit,
    ) {
        client.leases().inNamespace(NAMESPACE).withName(lockName).delete()
        try {
            block()
        } finally {
            client.leases().inNamespace(NAMESPACE).withName(lockName).delete()
        }
    }

    companion object {
        private const val NAMESPACE = "default"

        private val k3s: K3sServer by lazy { K3sServer.Launcher.k3s }
    }
}
