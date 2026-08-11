package io.bluetape4k.leader.k8s.contract

import io.bluetape4k.leader.contract.AbstractSuspendLockExtenderContractTest
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.k8s.KubernetesLeaseOptions
import io.bluetape4k.leader.k8s.KubernetesLeaseSuspendLeaderElector
import io.fabric8.kubernetes.client.KubernetesClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance

/**
 * Kubernetes Lease suspend LockExtender contract implementation.
 */
@Tag("k8s")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KubernetesLeaseSuspendLockExtenderContractTest : AbstractSuspendLockExtenderContractTest() {
    private val client: KubernetesClient = KubernetesContractSupport.newClient()

    override val elector: SuspendLeaderElector =
        KubernetesLeaseSuspendLeaderElector(client, KubernetesLeaseOptions(namespace = "default"))

    @AfterAll
    fun closeClient() {
        client.close()
    }
}
