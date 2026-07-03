package io.bluetape4k.leader.examples.k8slease

import io.bluetape4k.assertions.assertFailsWith
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.junit.jupiter.api.Test

class K8sLeaseValidationTest {

    @Test
    fun `Lease example validates namespace and lease names before client calls`() {
        KubernetesClientBuilder().build().use { client ->
            assertFailsWith<IllegalArgumentException> {
                K8sLeaseLeaderElectionExample(client = client, namespace = "InvalidNamespace")
            }

            val example = K8sLeaseLeaderElectionExample(client = client, namespace = "default")
            assertFailsWith<IllegalArgumentException> {
                example.tryAcquire("InvalidLease", "node-a")
            }
            assertFailsWith<IllegalArgumentException> {
                example.release("lease_name", "node-a")
            }
            assertFailsWith<IllegalArgumentException> {
                example.delete("x".repeat(64))
            }
        }
    }
}
