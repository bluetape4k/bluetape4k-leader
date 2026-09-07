package io.bluetape4k.leader.k8s

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.fabric8.kubernetes.api.model.coordination.v1.Lease
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseList
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.NamespaceableResource
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation
import io.fabric8.kubernetes.client.dsl.Resource
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class KubernetesLeaseAsyncLifecycleTest {

    @Test
    fun `single action completion keeps named event loop free while cleanup is blocked`() {
        val blocker = CleanupBlocker(block = true)
        val client = mockKubernetesClient(blocker)

        assertCompletionThreadIsolated(blocker) { executor, actionFuture, actionStarted ->
            KubernetesLeaseLeaderElector(client, singleOptions())
                .runAsyncIfLeader("lock-a", executor) {
                    actionStarted.countDown()
                    actionFuture
                }
        }
    }

    @Test
    fun `group action completion keeps named event loop free while cleanup is blocked`() {
        val blocker = CleanupBlocker(block = true)
        val client = mockKubernetesClient(blocker)

        assertCompletionThreadIsolated(blocker) { executor, actionFuture, actionStarted ->
            KubernetesLeaseLeaderGroupElector(client, groupOptions())
                .runAsyncIfLeader("lock-a", executor) {
                    actionStarted.countDown()
                    actionFuture
                }
        }
    }

    @Test
    fun `dispatcher rejection falls back and completes after exactly once cleanup`() {
        val cleanupCalls = AtomicInteger()
        val rejectionCalls = AtomicInteger()
        val rejectingExecutor = Executor {
            rejectionCalls.incrementAndGet()
            throw RejectedExecutionException("issue-900-cleanup-rejected")
        }

        val result = AsyncLeaseCleanupDispatcher.completeAfter(
            source = CompletableFuture.completedFuture("done"),
            executor = rejectingExecutor,
            cleanup = { cleanupCalls.incrementAndGet() },
        ) { value, _ -> value }

        result.get(2, TimeUnit.SECONDS) shouldBeEqualTo "done"
        result.isDone.shouldBeTrue()
        rejectionCalls.get() shouldBeEqualTo 1
        cleanupCalls.get() shouldBeEqualTo 1
    }

    @Test
    fun `dispatcher preserves action failure and suppresses cleanup failure`() {
        val actionFailure = IllegalStateException("action-failed")
        val cleanupFailure = IllegalStateException("cleanup-failed")
        val inlineExecutor = Executor { command -> command.run() }

        val result = AsyncLeaseCleanupDispatcher.completeAfter(
            source = CompletableFuture.failedFuture<String>(actionFailure),
            executor = inlineExecutor,
            cleanup = { throw cleanupFailure },
        ) { value, _ -> value }

        val thrown = assertFailsWith<CompletionException> { result.join() }

        thrown.cause shouldBeEqualTo actionFailure
        thrown.cause?.suppressed?.toList() shouldBeEqualTo listOf(cleanupFailure)
    }

    private fun assertCompletionThreadIsolated(
        blocker: CleanupBlocker,
        runAsync: (Executor, CompletableFuture<String>, CountDownLatch) -> CompletableFuture<String?>,
    ) {
        val eventLoop = Executors.newSingleThreadExecutor { task -> Thread(task, "issue-900-k8s-event-loop") }
        val actionFuture = CompletableFuture<String>()
        val actionStarted = CountDownLatch(1)
        val eventLoopProbe = CountDownLatch(1)

        try {
            val result = runAsync(eventLoop, actionFuture, actionStarted)
            actionStarted.await(2, TimeUnit.SECONDS).shouldBeTrue()
            eventLoop.execute { actionFuture.complete("done") }

            blocker.started.await(2, TimeUnit.SECONDS).shouldBeTrue()
            eventLoop.execute { eventLoopProbe.countDown() }
            eventLoopProbe.await(1, TimeUnit.SECONDS).shouldBeTrue()
            result.isDone.shouldBeFalse()
            (blocker.threadName.get() == "issue-900-k8s-event-loop").shouldBeFalse()

            blocker.release.countDown()
            result.get(2, TimeUnit.SECONDS) shouldBeEqualTo "done"
            blocker.updateCalls.get() shouldBeEqualTo 1
        } finally {
            blocker.release.countDown()
            eventLoop.shutdownNow()
        }
    }

    private fun singleOptions(): KubernetesLeaseOptions =
        KubernetesLeaseOptions(
            leaderOptions = LeaderElectionOptions(
                waitTime = Duration.ZERO,
                leaseTime = 10.seconds,
            ),
        )

    private fun groupOptions(): KubernetesLeaseGroupOptions =
        KubernetesLeaseGroupOptions(
            leaderGroupOptions = LeaderGroupElectionOptions(
                maxLeaders = 1,
                waitTime = Duration.ZERO,
                leaseTime = 10.seconds,
            ),
        )

    private fun mockKubernetesClient(blocker: CleanupBlocker): KubernetesClient {
        val client = mockk<KubernetesClient>()
        val leases = mockk<MixedOperation<Lease, LeaseList, Resource<Lease>>>()
        val namespaced = mockk<NonNamespaceOperation<Lease, LeaseList, Resource<Lease>>>()
        val named = mockk<Resource<Lease>>()
        val createResource = mockk<NamespaceableResource<Lease>>()
        val updateResource = mockk<NamespaceableResource<Lease>>()
        val createdLease = slot<Lease>()
        val updatedLease = slot<Lease>()
        val currentLease = AtomicReference<Lease?>()

        every { client.leases() } returns leases
        every { leases.inNamespace(any()) } returns namespaced
        every { namespaced.withName(any()) } returns named
        every { named.get() } answers { currentLease.get() }
        every { namespaced.resource(capture(createdLease)) } returns createResource
        every { createResource.create() } answers {
            createdLease.captured.also(currentLease::set)
        }
        every { client.resource(capture(updatedLease)) } returns updateResource
        every { updateResource.update() } answers {
            blocker.onUpdate()
            updatedLease.captured.also(currentLease::set)
        }

        return client
    }

    private class CleanupBlocker(
        private val block: Boolean = false,
    ) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val threadName = AtomicReference<String>()
        val updateCalls = AtomicInteger()

        fun onUpdate() {
            updateCalls.incrementAndGet()
            threadName.set(Thread.currentThread().name)
            started.countDown()
            if (block) {
                release.await(5, TimeUnit.SECONDS).shouldBeTrue()
            }
        }
    }
}
