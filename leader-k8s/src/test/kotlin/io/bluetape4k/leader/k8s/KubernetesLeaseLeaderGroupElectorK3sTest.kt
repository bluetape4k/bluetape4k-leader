package io.bluetape4k.leader.k8s

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.k8s.internal.KubernetesLeaseLock
import io.bluetape4k.leader.k8s.internal.KubernetesLeaseNames
import io.bluetape4k.testcontainers.infra.K3sServer
import io.fabric8.kubernetes.client.KubernetesClient
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Tag("k8s")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KubernetesLeaseLeaderGroupElectorK3sTest {

    companion object {
        private const val NAMESPACE = "default"

        private val k3s: K3sServer by lazy { K3sServer.Launcher.k3s }
    }

    @Test
    fun `runIfLeader acquires up to max leaders and releases for reacquire`() {
        k3s.kubernetesClient().use { client ->
            val lockName = randomLockName()
            withCleanGroupLeases(client, lockName, maxLeaders = 2) {
                val first = elector(client, nodeId = "node-a", maxLeaders = 2)
                val second = elector(client, nodeId = "node-b", maxLeaders = 2)
                val third = elector(client, nodeId = "node-c", maxLeaders = 2)

                val result = first.runIfLeader(lockName) {
                    val nested = second.runIfLeader(lockName) {
                        val state = first.state(lockName)
                        state.activeCount shouldBeEqualTo 2
                        state.leaders.size shouldBeEqualTo 2
                        third.runIfLeader(lockName) { "third" }.shouldBeNull()
                        "second"
                    }
                    nested shouldBeEqualTo "second"
                    "first"
                }
                val reacquired = third.runIfLeader(lockName) { "reacquired" }

                result shouldBeEqualTo "first"
                reacquired shouldBeEqualTo "reacquired"
                first.state(lockName).activeCount shouldBeEqualTo 0
            }
        }
    }

    @Test
    fun `slot leader id is written as group audit identity`() {
        k3s.kubernetesClient().use { client ->
            val lockName = randomLockName()
            withCleanGroupLeases(client, lockName, maxLeaders = 2) {
                val leaderId = "audit-node-a"
                val election = elector(client, nodeId = "node-a", maxLeaders = 2)

                val result = election.runIfLeaderResult(LeaderSlot(lockName, leaderId)) {
                    val state = election.state(lockName)

                    state.activeCount shouldBeEqualTo 1
                    state.leaders.any {
                        it.auditLeaderId == leaderId && it.nodeId == "node-a" && it.slot != null
                    }.shouldBeTrue()
                    "done"
                }

                (result is LeaderRunResult.Elected).shouldBeTrue()
                (result as LeaderRunResult.Elected).value shouldBeEqualTo "done"
                result.leaderId shouldBeEqualTo leaderId
            }
        }
    }

    @Test
    fun `expired group slot is taken over by another owner`() {
        k3s.kubernetesClient().use { client ->
            val lockName = randomLockName()
            withCleanGroupLeases(client, lockName, maxLeaders = 1) {
                val staleLock = KubernetesLeaseLock(
                    client = client,
                    namespace = NAMESPACE,
                    lockName = KubernetesLeaseNames.groupSlotLeaseName(lockName, slot = 0, maxLeaders = 1),
                    ownerToken = "owner-a-${Base58.randomString(8).lowercase()}",
                    auditLeaderId = "node-a",
                    nodeId = "node-a",
                    retryDelay = 10.milliseconds,
                    clock = Clock.systemUTC(),
                )

                staleLock.tryLock(waitTime = 100.milliseconds, leaseTime = 1.seconds).shouldBeTrue()
                Thread.sleep(1_250L)

                val result = elector(client, nodeId = "node-b", maxLeaders = 1).runIfLeader(lockName) { "node-b" }

                result shouldBeEqualTo "node-b"
            }
        }
    }

    @Test
    fun `async failure releases group slot for next attempt`() {
        k3s.kubernetesClient().use { client ->
            val lockName = randomLockName()
            withCleanGroupLeases(client, lockName, maxLeaders = 1) {
                val election = elector(client, nodeId = "node-a", maxLeaders = 1)

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

    private fun elector(
        client: KubernetesClient,
        nodeId: String,
        maxLeaders: Int,
        waitTime: Duration = 200.milliseconds,
        leaseTime: Duration = 1.seconds,
    ): KubernetesLeaseLeaderGroupElector =
        KubernetesLeaseLeaderGroupElector(
            client,
            KubernetesLeaseGroupOptions(
                namespace = NAMESPACE,
                retryDelay = 10.milliseconds,
                leaderGroupOptions = LeaderGroupElectionOptions(
                    maxLeaders = maxLeaders,
                    waitTime = waitTime,
                    leaseTime = leaseTime,
                    nodeId = nodeId,
                ),
            ),
        )

    private fun randomLockName(): String =
        "k8s-${Base58.randomString(10).lowercase()}"

    private inline fun withCleanGroupLeases(
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

    private fun deleteGroupLeases(client: KubernetesClient, lockName: String, maxLeaders: Int) {
        repeat(maxLeaders) { slot ->
            client.leases()
                .inNamespace(NAMESPACE)
                .withName(KubernetesLeaseNames.groupSlotLeaseName(lockName, slot, maxLeaders))
                .delete()
        }
    }
}
