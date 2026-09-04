package io.bluetape4k.leader.k8s.contract

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.k8s.KubernetesLeaseGroupOptions
import io.bluetape4k.leader.k8s.KubernetesLeaseLeaderElector
import io.bluetape4k.leader.k8s.KubernetesLeaseLeaderGroupElector
import io.bluetape4k.leader.k8s.KubernetesLeaseOptions
import io.fabric8.kubernetes.client.KubernetesClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Direct Kubernetes Lease executor overload coverage for single and group paths.
 */
@Tag("k8s")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KubernetesLeaseExecutorOverloadContractTest {
    private val client: KubernetesClient = KubernetesContractSupport.newClient()

    @Test
    fun singleExecutorOverloadPropagatesLeaderIdAndReleases() {
        val elector = KubernetesLeaseLeaderElector(
            client,
            KubernetesLeaseOptions(
                leaderOptions = LeaderElectionOptions(
                    waitTime = 1.seconds,
                    leaseTime = 5.seconds,
                    nodeId = "k8s-contract-single",
                ),
                namespace = "default",
            ),
        )
        val lockName = "k8s-executor-single-success-contract"

        val first = elector.runAsyncIfLeaderResult(
            LeaderSlot(lockName, "k8s-single-a"),
            VirtualThreadExecutor,
        ) {
            CompletableFuture.completedFuture("single-ok")
        }.join()

        first shouldBeInstanceOf LeaderRunResult.Elected::class
        (first as LeaderRunResult.Elected).value shouldBeEqualTo "single-ok"
        first.leaderId shouldBeEqualTo "k8s-single-a"

        val second = elector.runAsyncIfLeaderResult(
            LeaderSlot(lockName, "k8s-single-b"),
            VirtualThreadExecutor,
        ) {
            CompletableFuture.completedFuture("single-reacquired")
        }.join()

        second shouldBeInstanceOf LeaderRunResult.Elected::class
        (second as LeaderRunResult.Elected).value shouldBeEqualTo "single-reacquired"
        second.leaderId shouldBeEqualTo "k8s-single-b"
    }

    @Test
    fun groupExecutorOverloadPropagatesLeaderIdAndReleases() {
        val elector = KubernetesLeaseLeaderGroupElector(
            client,
            KubernetesLeaseGroupOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(
                    maxLeaders = 2,
                    waitTime = 1.seconds,
                    leaseTime = 5.seconds,
                    nodeId = "k8s-contract-group",
                ),
                namespace = "default",
            ),
        )
        val lockName = "k8s-executor-group-success-contract"

        val first = elector.runAsyncIfLeaderResult(
            LeaderSlot(lockName, "k8s-group-a"),
            VirtualThreadExecutor,
        ) {
            CompletableFuture.completedFuture("group-ok")
        }.join()

        first shouldBeInstanceOf LeaderRunResult.Elected::class
        (first as LeaderRunResult.Elected).value shouldBeEqualTo "group-ok"
        first.leaderId shouldBeEqualTo "k8s-group-a"

        val second = elector.runAsyncIfLeaderResult(
            LeaderSlot(lockName, "k8s-group-b"),
            VirtualThreadExecutor,
        ) {
            CompletableFuture.completedFuture("group-reacquired")
        }.join()

        second shouldBeInstanceOf LeaderRunResult.Elected::class
        (second as LeaderRunResult.Elected).value shouldBeEqualTo "group-reacquired"
        second.leaderId shouldBeEqualTo "k8s-group-b"
    }

    @Test
    fun singleExecutorRejectsSecondSubmissionAndReleases() {
        val elector = KubernetesLeaseLeaderElector(
            client,
            KubernetesLeaseOptions(
                leaderOptions = LeaderElectionOptions(
                    waitTime = 1.seconds,
                    leaseTime = 5.seconds,
                    nodeId = "k8s-contract-single",
                ),
                namespace = "default",
            ),
        )
        val lockName = "k8s-executor-single-contract"
        val worker = Executors.newSingleThreadExecutor()
        val submissions = AtomicInteger()
        val actionInvoked = AtomicBoolean()
        val executor = Executor { command ->
            if (submissions.incrementAndGet() == 1) {
                worker.execute(command)
            } else {
                throw RejectedExecutionException("second submission rejected")
            }
        }

        try {
            val resultFuture = runCatching {
                elector.runAsyncIfLeader(lockName, executor) {
                    actionInvoked.set(true)
                    CompletableFuture.completedFuture("should-not-run")
                }
            }.getOrElse { CompletableFuture.failedFuture(it) }

            val failure = assertFailsWith<CompletionException> { resultFuture.join() }
            failure.cause.shouldBeInstanceOf<RejectedExecutionException>()
            actionInvoked.get() shouldBeEqualTo false

            val result = elector.runAsyncIfLeaderResult(LeaderSlot(lockName, "k8s-single-b")) {
                CompletableFuture.completedFuture("single-reacquired")
            }.join()
            result shouldBeInstanceOf LeaderRunResult.Elected::class
            (result as LeaderRunResult.Elected).value shouldBeEqualTo "single-reacquired"
            result.leaderId shouldBeEqualTo "k8s-single-b"
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun groupExecutorRejectsSecondSubmissionAndReleases() {
        val elector = KubernetesLeaseLeaderGroupElector(
            client,
            KubernetesLeaseGroupOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(
                    maxLeaders = 2,
                    waitTime = 1.seconds,
                    leaseTime = 5.seconds,
                    nodeId = "k8s-contract-group",
                ),
                namespace = "default",
            ),
        )
        val lockName = "k8s-executor-group-contract"
        val worker = Executors.newSingleThreadExecutor()
        val submissions = AtomicInteger()
        val actionInvoked = AtomicBoolean()
        val executor = Executor { command ->
            if (submissions.incrementAndGet() == 1) {
                worker.execute(command)
            } else {
                throw RejectedExecutionException("second submission rejected")
            }
        }

        try {
            val resultFuture = runCatching {
                elector.runAsyncIfLeader(lockName, executor) {
                    actionInvoked.set(true)
                    CompletableFuture.completedFuture("should-not-run")
                }
            }.getOrElse { CompletableFuture.failedFuture(it) }

            val failure = assertFailsWith<CompletionException> { resultFuture.join() }
            failure.cause.shouldBeInstanceOf<RejectedExecutionException>()
            actionInvoked.get() shouldBeEqualTo false

            val result = elector.runAsyncIfLeaderResult(LeaderSlot(lockName, "k8s-group-b")) {
                CompletableFuture.completedFuture("group-reacquired")
            }.join()
            result shouldBeInstanceOf LeaderRunResult.Elected::class
            (result as LeaderRunResult.Elected).value shouldBeEqualTo "group-reacquired"
            result.leaderId shouldBeEqualTo "k8s-group-b"
        } finally {
            worker.shutdownNow()
        }
    }

    @AfterAll
    fun closeClient() {
        client.close()
    }
}
