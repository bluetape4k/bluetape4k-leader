package io.bluetape4k.leader.k8s

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.testcontainers.infra.K3sServer
import io.fabric8.kubernetes.client.KubernetesClient
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Tag("k8s")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KubernetesLeaseSuspendLeaderElectorK3sTest {

    companion object {
        private const val NAMESPACE = "default"

        private val k3s: K3sServer by lazy { K3sServer.Launcher.k3s }
    }

    @Test
    fun `runIfLeader blocks concurrent suspend holder and releases for reacquire`() = runSuspendIO {
        k3s.kubernetesClient().use { client ->
            val lockName = randomLockName()
            withCleanLease(client, lockName) {
                val first = elector(client, nodeId = "node-a")
                val second = elector(client, nodeId = "node-b")

                val result = first.runIfLeader(lockName) {
                    second.runIfLeader(lockName) { "second" }.shouldBeNull()
                    "first"
                }
                val reacquired = second.runIfLeader(lockName) { "reacquired" }

                result shouldBeEqualTo "first"
                reacquired shouldBeEqualTo "reacquired"
            }
        }
    }

    @Test
    fun `cancellation releases lease in NonCancellable cleanup`() = runSuspendIO {
        k3s.kubernetesClient().use { client ->
            val lockName = randomLockName()
            withCleanLease(client, lockName) {
                val election = elector(client, nodeId = "node-a")

                kotlin.runCatching {
                    withTimeout(100.milliseconds) {
                        election.runIfLeader(lockName) {
                            delay(1.seconds)
                        }
                    }
                }.onFailure { e ->
                    if (e !is TimeoutCancellationException) {
                        throw e
                    }
                }

                val result = election.runIfLeader(lockName) { "recovered" }
                result shouldBeEqualTo "recovered"
            }
        }
    }

    private fun elector(
        client: KubernetesClient,
        nodeId: String,
        waitTime: Duration = 150.milliseconds,
        leaseTime: Duration = 1.seconds,
    ): KubernetesLeaseSuspendLeaderElector =
        KubernetesLeaseSuspendLeaderElector(
            client,
            KubernetesLeaseOptions(
                namespace = NAMESPACE,
                retryDelay = 10.milliseconds,
                leaderOptions = LeaderElectionOptions(
                    waitTime = waitTime,
                    leaseTime = leaseTime,
                    nodeId = nodeId,
                ),
            ),
        )

    private fun randomLockName(): String =
        "k8s-${Base58.randomString(10).lowercase()}"

    private inline fun withCleanLease(
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
}
