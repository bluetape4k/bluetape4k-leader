package io.bluetape4k.leader.k8s

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.k8s.internal.KubernetesLeaseLock
import io.bluetape4k.testcontainers.infra.K3sServer
import io.fabric8.kubernetes.client.KubernetesClient
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Tag("k8s")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KubernetesLeaseLeaderElectorK3sTest {

    companion object {
        private const val NAMESPACE = "default"

        private val k3s: K3sServer by lazy { K3sServer.Launcher.k3s }
    }

    @Test
    fun `runIfLeader blocks concurrent holder and releases for reacquire`() {
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
    fun `same node still uses acquisition token so concurrent body is skipped`() {
        k3s.kubernetesClient().use { client ->
            val lockName = randomLockName()
            withCleanLease(client, lockName) {
                val first = elector(client, nodeId = "same-node")
                val second = elector(client, nodeId = "same-node")

                val skipped = first.runIfLeader(lockName) {
                    second.runIfLeader(lockName) { "duplicate" }
                }

                skipped.shouldBeNull()
            }
        }
    }

    @Test
    fun `slot leader id is written as audit identity while owner token fences release`() {
        k3s.kubernetesClient().use { client ->
            val lockName = randomLockName()
            withCleanLease(client, lockName) {
                val leaderId = "audit-node-a"
                val election = elector(client, nodeId = "node-a")

                val result = election.runIfLeaderResult(LeaderSlot(lockName, leaderId)) {
                    val state = election.state(lockName)

                    state.isOccupied.shouldBeTrue()
                    state.leader?.auditLeaderId shouldBeEqualTo leaderId
                    state.leader?.nodeId shouldBeEqualTo "node-a"
                    "done"
                }

                (result is LeaderRunResult.Elected).shouldBeTrue()
                (result as LeaderRunResult.Elected).value shouldBeEqualTo "done"
                result.leaderId shouldBeEqualTo leaderId
            }
        }
    }

    @Test
    fun `expired lease is taken over by another owner`() {
        k3s.kubernetesClient().use { client ->
            val lockName = randomLockName()
            withCleanLease(client, lockName) {
                val staleLock = KubernetesLeaseLock(
                    client = client,
                    namespace = NAMESPACE,
                    lockName = lockName,
                    ownerToken = "owner-a-${Base58.randomString(8).lowercase()}",
                    auditLeaderId = "node-a",
                    nodeId = "node-a",
                    retryDelay = 10.milliseconds,
                    clock = Clock.systemUTC(),
                )

                staleLock.tryLock(waitTime = 100.milliseconds, leaseTime = 1.seconds).shouldBeTrue()
                Thread.sleep(1_250L)

                val result = elector(client, nodeId = "node-b").runIfLeader(lockName) { "node-b" }

                result shouldBeEqualTo "node-b"
            }
        }
    }

    @Test
    fun `action failure releases lease for next attempt`() {
        k3s.kubernetesClient().use { client ->
            val lockName = randomLockName()
            withCleanLease(client, lockName) {
                val election = elector(client, nodeId = "node-a")

                assertFailsWith<IllegalStateException> {
                    election.runIfLeader(lockName) {
                        throw IllegalStateException("boom")
                    }
                }

                val result = election.runIfLeader(lockName) { "recovered" }
                result shouldBeEqualTo "recovered"
            }
        }
    }

    @Test
    fun `async failure releases lease for next attempt`() {
        k3s.kubernetesClient().use { client ->
            val lockName = randomLockName()
            withCleanLease(client, lockName) {
                val election = elector(client, nodeId = "node-a")

                assertFailsWith<CompletionException> {
                    election.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
                        CompletableFuture.failedFuture<Int>(IllegalStateException("boom"))
                    }.join()
                }

                val result = election.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
                    CompletableFuture.completedFuture("recovered")
                }.get(5, TimeUnit.SECONDS)
                result shouldBeEqualTo "recovered"
            }
        }
    }

    @Test
    fun `async result propagates cancellation and releases lease`() {
        k3s.kubernetesClient().use { client ->
            val lockName = randomLockName()
            withCleanLease(client, lockName) {
                val election = elector(client, nodeId = "node-a")

                val failure = assertFailsWith<ExecutionException> {
                    election.runAsyncIfLeaderResult(LeaderSlot(lockName, "node-a"), VirtualThreadExecutor) {
                        CompletableFuture.failedFuture<Int>(java.util.concurrent.CancellationException("cancelled"))
                    }.get(5, TimeUnit.SECONDS)
                }

                (failure.cause is java.util.concurrent.CancellationException).shouldBeTrue()
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
    ): KubernetesLeaseLeaderElector =
        KubernetesLeaseLeaderElector(
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
