package io.bluetape4k.leader.k8s.contract

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.k8s.KubernetesLeaseGroupOptions
import io.bluetape4k.leader.k8s.KubernetesLeaseLeaderElector
import io.bluetape4k.leader.k8s.KubernetesLeaseLeaderGroupElector
import io.bluetape4k.leader.k8s.KubernetesLeaseOptions
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.closeSafe
import io.fabric8.kubernetes.client.KubernetesClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
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

    companion object: KLogging()

    private val client: KubernetesClient = KubernetesContractSupport.newClient()

    @AfterAll
    fun closeClient() {
        client.closeSafe()
    }

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
            completableFutureOf("single-ok")
        }.join()

        first.shouldBeInstanceOf<LeaderRunResult.Elected<String>>()
        first.value shouldBeEqualTo "single-ok"
        first.leaderId shouldBeEqualTo "k8s-single-a"

        val second = elector.runAsyncIfLeaderResult(
            LeaderSlot(lockName, "k8s-single-b"),
            VirtualThreadExecutor,
        ) {
            completableFutureOf("single-reacquired")
        }.join()

        second.shouldBeInstanceOf<LeaderRunResult.Elected<String>>()
        second.value shouldBeEqualTo "single-reacquired"
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
            completableFutureOf("group-ok")
        }.join()

        first.shouldBeInstanceOf<LeaderRunResult.Elected<String>>()
        first.value shouldBeEqualTo "group-ok"
        first.leaderId shouldBeEqualTo "k8s-group-a"

        val second = elector.runAsyncIfLeaderResult(
            LeaderSlot(lockName, "k8s-group-b"),
            VirtualThreadExecutor,
        ) {
            completableFutureOf("group-reacquired")
        }.join()

        second.shouldBeInstanceOf<LeaderRunResult.Elected<String>>()
        second.value shouldBeEqualTo "group-reacquired"
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
                    completableFutureOf("should-not-run")
                }
            }.getOrElse { failedCompletableFutureOf(it) }

            val failure = assertFailsWith<CompletionException> { resultFuture.join() }
            failure.cause.shouldBeInstanceOf<RejectedExecutionException>()
            actionInvoked.get().shouldBeFalse()

            val result = elector.runAsyncIfLeaderResult(LeaderSlot(lockName, "k8s-single-b")) {
                completableFutureOf("single-reacquired")
            }.join()
            result.shouldBeInstanceOf<LeaderRunResult.Elected<String>>()
            result.value shouldBeEqualTo "single-reacquired"
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
                    completableFutureOf("should-not-run")
                }
            }.getOrElse { failedCompletableFutureOf(it) }

            val failure = assertFailsWith<CompletionException> { resultFuture.join() }
            failure.cause.shouldBeInstanceOf<RejectedExecutionException>()
            actionInvoked.get().shouldBeFalse()

            val result = elector.runAsyncIfLeaderResult(LeaderSlot(lockName, "k8s-group-b")) {
                completableFutureOf("group-reacquired")
            }.join()
            result.shouldBeInstanceOf<LeaderRunResult.Elected<String>>()
            result.value shouldBeEqualTo "group-reacquired"
            result.leaderId shouldBeEqualTo "k8s-group-b"
        } finally {
            worker.shutdownNow()
        }
    }
}
