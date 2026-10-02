package io.bluetape4k.leader.examples.k8slease

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.logging.KLogging
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.junit.jupiter.api.Test

class K8sLeaseValidationTest {

    companion object: KLogging()

    @Test
    fun `Lease example validates namespace and lease names before client calls`() {
        KubernetesClientBuilder().build().use { client ->
            assertFailsWith<IllegalArgumentException> {
                K8sLeaseLeaderElectionExample(client = client, namespace = "InvalidNamespace")
            }

            val example = K8sLeaseLeaderElectionExample(client = client, namespace = "default")

            // leaseName는 소문자만
            assertFailsWith<IllegalArgumentException> {
                example.tryAcquire("InvalidLease", "node-a")
            }
            // leaseName는 `-` 만 가능, `_` 는 불가  
            assertFailsWith<IllegalArgumentException> {
                example.release("lease_name", "node-a")
            }
            // leaseName는 63자 제한
            assertFailsWith<IllegalArgumentException> {
                example.delete("x".repeat(64))
            }
        }
    }
}
