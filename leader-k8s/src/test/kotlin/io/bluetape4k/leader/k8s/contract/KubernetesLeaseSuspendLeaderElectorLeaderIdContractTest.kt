package io.bluetape4k.leader.k8s.contract

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.contract.AbstractSuspendLeaderElectorLeaderIdContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.k8s.KubernetesLeaseOptions
import io.bluetape4k.leader.k8s.KubernetesLeaseSuspendLeaderElector
import io.fabric8.kubernetes.client.KubernetesClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance

/**
 * Kubernetes Lease suspend leader-id contract implementation.
 */
@Tag("k8s")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KubernetesLeaseSuspendLeaderElectorLeaderIdContractTest : AbstractSuspendLeaderElectorLeaderIdContractTest() {
    private val client: KubernetesClient = KubernetesContractSupport.newClient()

    override fun createElector(options: LeaderElectionOptions): SuspendLeaderElector =
        KubernetesLeaseSuspendLeaderElector(
            client,
            KubernetesLeaseOptions(leaderOptions = options, namespace = "default"),
        )

    @AfterAll
    fun closeClient() {
        client.close()
    }
}
