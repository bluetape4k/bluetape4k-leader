package io.bluetape4k.leader.k8s.contract

import io.bluetape4k.leader.AsyncLeaderElector
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.contract.AbstractAsyncLeaderElectorLeaderIdContractTest
import io.bluetape4k.leader.k8s.KubernetesLeaseLeaderElector
import io.bluetape4k.leader.k8s.KubernetesLeaseOptions
import io.fabric8.kubernetes.client.KubernetesClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance

/**
 * Kubernetes Lease async leader-id contract implementation.
 */
@Tag("k8s")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KubernetesLeaseAsyncLeaderElectorLeaderIdContractTest : AbstractAsyncLeaderElectorLeaderIdContractTest() {
    private val client: KubernetesClient = KubernetesContractSupport.newClient()

    override fun createElector(options: LeaderElectionOptions): AsyncLeaderElector =
        KubernetesLeaseLeaderElector(
            client,
            KubernetesLeaseOptions(leaderOptions = options, namespace = "default"),
        )

    @AfterAll
    fun closeClient() {
        client.close()
    }
}
