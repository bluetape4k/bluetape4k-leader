package io.bluetape4k.leader.k8s

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.k8s.internal.KubernetesLeaseNames
import io.bluetape4k.logging.KLogging
import io.fabric8.kubernetes.client.KubernetesClient
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractKubernetesTest {

    companion object: KLogging() {
        const val NAMESPACE = "default"
    }

    protected fun randomLockName(): String =
        "k8s-${Base58.randomString(12).lowercase()}"

    protected inline fun withCleanLease(
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

    protected inline fun withCleanGroupLeases(
        client: KubernetesClient,
        lockName: String,
        maxLeaders: Int,
        block: () -> Unit,
    ) {
        deleteGroupLeases(client, lockName, maxLeaders)
        try {
            block()
        } finally {
            deleteGroupLeases(client, lockName, maxLeaders)
        }
    }

    protected fun deleteGroupLeases(client: KubernetesClient, lockName: String, maxLeaders: Int) {
        repeat(maxLeaders) { slot ->
            client.leases()
                .inNamespace(NAMESPACE)
                .withName(KubernetesLeaseNames.groupSlotLeaseName(lockName, slot, maxLeaders))
                .delete()
        }
    }

}
