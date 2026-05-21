package io.bluetape4k.leader.examples.k8slease

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.testcontainers.infra.K3sServer
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration

@Tag("k8s")
class K8sLeaseLeaderElectionExampleTest {

    companion object {
        private const val NAMESPACE = "default"

        private val k3s: K3sServer by lazy { K3sServer.Launcher.k3s }
    }

    @Test
    fun `Lease acquire conflict release and reacquire`() {
        k3s.kubernetesClient().use { client ->
            val example = K8sLeaseLeaderElectionExample(
                client = client,
                namespace = NAMESPACE,
                leaseDuration = Duration.ofSeconds(30),
            )
            val leaseName = "leader-example-${Base58.randomString(8).lowercase()}"

            example.delete(leaseName)
            try {
                val first = example.tryAcquire(leaseName, "node-a")
                val second = example.tryAcquire(leaseName, "node-b")
                val released = example.release(leaseName, "node-a")
                val third = example.tryAcquire(leaseName, "node-b")

                first.outcome shouldBeEqualTo LeaseOutcome.ACQUIRED
                first.holderIdentity shouldBeEqualTo "node-a"
                second.outcome shouldBeEqualTo LeaseOutcome.CONFLICT
                second.holderIdentity shouldBeEqualTo "node-a"
                released.shouldBeTrue()
                third.outcome shouldBeEqualTo LeaseOutcome.ACQUIRED
                third.holderIdentity shouldBeEqualTo "node-b"
            } finally {
                example.delete(leaseName)
            }
        }
    }
}
