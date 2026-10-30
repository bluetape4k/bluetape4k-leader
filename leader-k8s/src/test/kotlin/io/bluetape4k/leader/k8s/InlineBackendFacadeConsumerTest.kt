package io.bluetape4k.leader.k8s

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.fabric8.kubernetes.client.KubernetesClient
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class InlineBackendFacadeConsumerTest : AbstractKubernetesTest() {

    private fun callRunIfLeaderExplicit(client: KubernetesClient, lockName: String, action: () -> String): String? =
        client.runIfLeader(lockName, KubernetesLeaseOptions.Default, action)

    private fun callRunIfLeaderDefault(client: KubernetesClient, lockName: String, action: () -> String): String? =
        client.runIfLeader(lockName, action = action)

    private fun callRunAsyncIfLeaderExplicit(client: KubernetesClient, lockName: String, action: () -> CompletableFuture<String>) =
        client.runAsyncIfLeader(lockName, VirtualThreadExecutor, KubernetesLeaseOptions.Default, action)

    private fun callRunAsyncIfLeaderDefault(client: KubernetesClient, lockName: String, action: () -> CompletableFuture<String>) =
        client.runAsyncIfLeader(lockName, action = action)

    private fun callRunAsyncIfLeaderGroupExplicit(client: KubernetesClient, lockName: String, action: () -> CompletableFuture<String>) =
        client.runAsyncIfLeaderGroup(lockName, KubernetesLeaseGroupOptions.Default, VirtualThreadExecutor, action)

    private fun callRunAsyncIfLeaderGroupDefault(client: KubernetesClient, lockName: String, action: () -> CompletableFuture<String>) =
        client.runAsyncIfLeaderGroup(lockName, action = action)

    private suspend fun callSuspendRunIfLeaderExplicit(client: KubernetesClient, lockName: String, action: suspend () -> String) =
        client.suspendRunIfLeader(lockName, KubernetesLeaseOptions.Default, action)

    private suspend fun callSuspendRunIfLeaderDefault(client: KubernetesClient, lockName: String, action: suspend () -> String) =
        client.suspendRunIfLeader(lockName, action = action)

    private suspend fun callSuspendRunIfLeaderGroupExplicit(client: KubernetesClient, lockName: String, action: suspend () -> String) =
        client.suspendRunIfLeaderGroup(lockName, KubernetesLeaseGroupOptions.Default, action)

    private suspend fun callSuspendRunIfLeaderGroupDefault(client: KubernetesClient, lockName: String, action: suspend () -> String) =
        client.suspendRunIfLeaderGroup(lockName, action = action)

    @Test
    fun `source consumers call every lease facade and delegate each callback once`() = runSuspendIO {
        KubernetesContainers.newClient().use { client ->
            val calls = AtomicInteger()
            val next: () -> String = { "call-${calls.incrementAndGet()}" }
            val nextAsync: () -> CompletableFuture<String> = { CompletableFuture.completedFuture(next()) }

            callRunIfLeaderExplicit(client, randomLockName(), next) shouldBeEqualTo "call-1"
            callRunAsyncIfLeaderExplicit(client, randomLockName(), nextAsync).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-2"
            callRunAsyncIfLeaderGroupExplicit(client, randomLockName(), nextAsync).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-3"
            callSuspendRunIfLeaderExplicit(client, randomLockName()) { next() } shouldBeEqualTo "call-4"
            callSuspendRunIfLeaderGroupExplicit(client, randomLockName()) { next() } shouldBeEqualTo "call-5"

            callRunIfLeaderDefault(client, randomLockName(), next) shouldBeEqualTo "call-6"
            callRunAsyncIfLeaderDefault(client, randomLockName(), nextAsync).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-7"
            callRunAsyncIfLeaderGroupDefault(client, randomLockName(), nextAsync).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-8"
            callSuspendRunIfLeaderDefault(client, randomLockName()) { next() } shouldBeEqualTo "call-9"
            callSuspendRunIfLeaderGroupDefault(client, randomLockName()) { next() } shouldBeEqualTo "call-10"

            calls.get() shouldBeEqualTo 10
        }
    }

    @Test
    fun `facades skip callbacks during single and group contention`() {
        KubernetesContainers.newClient().use { client ->
            val executor = Executors.newSingleThreadExecutor()
                try {

                    val singleLockName = randomLockName()
                    withCleanLease(client, singleLockName) {
                        val holder = KubernetesLeaseLeaderElector(
                    client,
                    KubernetesLeaseOptions(
                        namespace = NAMESPACE,
                        leaderOptions = LeaderElectionOptions(
                            waitTime = 1.seconds,
                            leaseTime = 5.seconds,
                            nodeId = "issue-994-single-holder",
                        ),
                    ),
                        )
                        val started = CountDownLatch(1)
                        val release = CountDownLatch(1)
                        val holderFuture = executor.submit {
                            holder.runIfLeader(singleLockName) {
                                started.countDown()
                                release.await()
                                "holder"
                            }
                        }

                        try {
                            started.await(5, TimeUnit.SECONDS).shouldBeTrue()
                            val calls = AtomicInteger()
                            client.runIfLeader(
                                singleLockName,
                                options = KubernetesLeaseOptions(
                                    namespace = NAMESPACE,
                                    leaderOptions = LeaderElectionOptions(
                                        waitTime = 150.milliseconds,
                                        leaseTime = 5.seconds,
                                        nodeId = "issue-994-single-contender",
                                    ),
                                ),
                            ) {
                                calls.incrementAndGet()
                                "must-not-run"
                            }.shouldBeNull()
                            calls.get() shouldBeEqualTo 0
                        } finally {
                            release.countDown()
                            holderFuture.get(5, TimeUnit.SECONDS)
                        }
                    }

                    val groupLockName = randomLockName()
                    withCleanGroupLeases(client, groupLockName, maxLeaders = 1) {
                        val holder = KubernetesLeaseLeaderGroupElector(
                            client,
                            KubernetesLeaseGroupOptions(
                                namespace = NAMESPACE,
                                leaderGroupOptions = LeaderGroupElectionOptions(
                                    maxLeaders = 1,
                                    waitTime = 1.seconds,
                                    leaseTime = 5.seconds,
                                    nodeId = "issue-994-group-holder",
                                ),
                            ),
                        )
                        val started = CountDownLatch(1)
                        val release = CountDownLatch(1)
                        val holderFuture = executor.submit {
                            holder.runIfLeader(groupLockName) {
                                started.countDown()
                                release.await()
                                "holder"
                            }
                        }

                        try {
                            started.await(5, TimeUnit.SECONDS).shouldBeTrue()
                            val calls = AtomicInteger()
                            client.runAsyncIfLeaderGroup(
                                groupLockName,
                                options = KubernetesLeaseGroupOptions(
                                    namespace = NAMESPACE,
                                    leaderGroupOptions = LeaderGroupElectionOptions(
                                        maxLeaders = 1,
                                        waitTime = 150.milliseconds,
                                        leaseTime = 5.seconds,
                                        nodeId = "issue-994-group-contender",
                                    ),
                                ),
                                executor = VirtualThreadExecutor,
                            ) {
                                calls.incrementAndGet()
                                CompletableFuture.completedFuture("must-not-run")
                            }.get(5, TimeUnit.SECONDS).shouldBeNull()
                            calls.get() shouldBeEqualTo 0
                        } finally {
                            release.countDown()
                            holderFuture.get(5, TimeUnit.SECONDS)
                        }
                    }
                } finally {
                    executor.shutdownNow()
                }
        }
    }

    @Test
    fun `async facade cancellation releases the lease and callback failure is propagated`() {
        KubernetesContainers.newClient().use { client ->
            val lockName = randomLockName()
            withCleanLease(client, lockName) {
                val options = KubernetesLeaseOptions(
                    namespace = NAMESPACE,
                    leaderOptions = LeaderElectionOptions(
                        waitTime = 1.seconds,
                        leaseTime = 5.seconds,
                        nodeId = "issue-994-async",
                    ),
                )
                val failure = IllegalStateException("issue-994 kubernetes failure")
                assertFailsWith<IllegalStateException> {
                    client.runIfLeader(lockName, options) { throw failure }
                }
                client.runIfLeader(lockName, options) { "after-failure" } shouldBeEqualTo "after-failure"

                val actionStarted = CountDownLatch(1)
                val action = CompletableFuture<String>()
                val result = client.runAsyncIfLeader(
                    lockName,
                    options = options,
                    executor = VirtualThreadExecutor,
                ) {
                    actionStarted.countDown()
                    action
                }
                actionStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                result.cancel(false).shouldBeTrue()
                await atMost 5.seconds until { action.isCancelled }
                client.runIfLeader(lockName, options) { "after-cancel" } shouldBeEqualTo "after-cancel"
                action.cancel(true)
            }
        }
    }

    @Test
    fun `async group facade failure and cancellation release the lease`() {
        KubernetesContainers.newClient().use { client ->
            val lockName = randomLockName()
            withCleanGroupLeases(client, lockName, maxLeaders = 1) {
                val options = KubernetesLeaseGroupOptions(
                    namespace = NAMESPACE,
                    leaderGroupOptions = LeaderGroupElectionOptions(
                        maxLeaders = 1,
                        waitTime = 1.seconds,
                        leaseTime = 5.seconds,
                        nodeId = "issue-994-group-lifecycle",
                    ),
                )
                val failure = IllegalStateException("issue-994 kubernetes group failure")
                val failed = client.runAsyncIfLeaderGroup(
                    lockName,
                    options = options,
                    executor = VirtualThreadExecutor,
                ) {
                    CompletableFuture<String>().also { it.completeExceptionally(failure) }
                }
                val thrown = assertFailsWith<ExecutionException> { failed.get(5, TimeUnit.SECONDS) }
                thrown.cause shouldBeEqualTo failure

                client.runAsyncIfLeaderGroup(lockName, options = options, executor = VirtualThreadExecutor) {
                    CompletableFuture.completedFuture("after-failure")
                }.get(5, TimeUnit.SECONDS) shouldBeEqualTo "after-failure"

                val actionStarted = CountDownLatch(1)
                val action = CompletableFuture<String>()
                val result = client.runAsyncIfLeaderGroup(
                    lockName,
                    options = options,
                    executor = VirtualThreadExecutor,
                ) {
                    actionStarted.countDown()
                    action
                }
                actionStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                result.cancel(false).shouldBeTrue()
                await atMost 5.seconds until { action.isCancelled }
                client.runAsyncIfLeaderGroup(lockName, options = options, executor = VirtualThreadExecutor) {
                    CompletableFuture.completedFuture("after-cancel")
                }.get(5, TimeUnit.SECONDS) shouldBeEqualTo "after-cancel"
                action.cancel(true)
            }
        }
    }

    @Test
    fun `suspend facade cancellation releases the lease`() = runSuspendIO {
        KubernetesContainers.newClient().use { client ->
            val lockName = randomLockName()
            withCleanLease(client, lockName) {
                val options = KubernetesLeaseOptions(
                    namespace = NAMESPACE,
                    leaderOptions = LeaderElectionOptions(
                        waitTime = 1.seconds,
                        leaseTime = 5.seconds,
                        nodeId = "issue-994-suspend",
                    ),
                )
                assertFailsWith<TimeoutCancellationException> {
                    withTimeout(100.milliseconds) {
                        client.suspendRunIfLeader(lockName, options) {
                            delay(10.seconds)
                        }
                    }
                }
                client.suspendRunIfLeader(lockName, options) { "reacquired" } shouldBeEqualTo "reacquired"
            }
        }
    }
}
