package io.bluetape4k.leader.k8s.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.contract.AbstractLeaderGroupElectorLeaderIdContractTest
import io.bluetape4k.leader.k8s.KubernetesLeaseGroupOptions
import io.bluetape4k.leader.k8s.KubernetesLeaseLeaderGroupElector
import io.fabric8.kubernetes.client.KubernetesClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance

/**
 * Kubernetes Lease blocking group leader-id contract implementation.
 */
@Tag("k8s")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KubernetesLeaseLeaderGroupElectorLeaderIdContractTest : AbstractLeaderGroupElectorLeaderIdContractTest() {
    private val client: KubernetesClient = KubernetesContractSupport.newClient()

    override fun createElector(options: LeaderGroupElectionOptions): LeaderGroupElector =
        KubernetesLeaseLeaderGroupElector(
            client,
            KubernetesLeaseGroupOptions(leaderGroupOptions = options, namespace = "default"),
        )

    @AfterAll
    fun closeClient() {
        client.close()
    }
}
