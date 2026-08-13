package io.bluetape4k.leader.k8s.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.contract.AbstractSuspendGroupLockExtenderContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.k8s.KubernetesLeaseGroupOptions
import io.bluetape4k.leader.k8s.KubernetesLeaseSuspendLeaderGroupElector
import io.fabric8.kubernetes.client.KubernetesClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance

/**
 * Kubernetes Lease suspend group LockExtender contract implementation.
 */
@Tag("k8s")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KubernetesLeaseSuspendGroupLockExtenderContractTest : AbstractSuspendGroupLockExtenderContractTest() {
    private val client: KubernetesClient = KubernetesContractSupport.newClient()

    override val elector: SuspendLeaderGroupElector =
        KubernetesLeaseSuspendLeaderGroupElector(
            client,
            KubernetesLeaseGroupOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2),
                namespace = "default",
            ),
        )

    @AfterAll
    fun closeClient() {
        client.close()
    }
}
