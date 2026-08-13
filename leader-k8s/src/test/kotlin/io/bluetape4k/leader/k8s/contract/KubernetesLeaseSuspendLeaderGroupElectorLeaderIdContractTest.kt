package io.bluetape4k.leader.k8s.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.contract.AbstractSuspendLeaderGroupElectorLeaderIdContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.k8s.KubernetesLeaseGroupOptions
import io.bluetape4k.leader.k8s.KubernetesLeaseSuspendLeaderGroupElector
import io.fabric8.kubernetes.client.KubernetesClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance

/**
 * Kubernetes Lease suspend group leader-id contract implementation.
 */
@Tag("k8s")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KubernetesLeaseSuspendLeaderGroupElectorLeaderIdContractTest : AbstractSuspendLeaderGroupElectorLeaderIdContractTest() {
    private val client: KubernetesClient = KubernetesContractSupport.newClient()

    override fun createElector(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        KubernetesLeaseSuspendLeaderGroupElector(
            client,
            KubernetesLeaseGroupOptions(leaderGroupOptions = options, namespace = "default"),
        )

    @AfterAll
    fun closeClient() {
        client.close()
    }
}
