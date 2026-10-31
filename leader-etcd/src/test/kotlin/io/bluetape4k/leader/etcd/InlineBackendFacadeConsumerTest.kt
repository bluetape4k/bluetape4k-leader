package io.bluetape4k.leader.etcd

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class InlineBackendFacadeConsumerTest : AbstractEtcdLeaderTest() {

    private fun singleOptions(
        prefix: String = "/issue-994/single-${randomName()}",
        leaderOptions: LeaderElectionOptions = LeaderElectionOptions.Default,
    ) = EtcdLeaderElectionOptions(keyPrefix = prefix, leaderOptions = leaderOptions)

    private fun groupOptions(
        prefix: String = "/issue-994/group-${randomName()}",
        leaderGroupOptions: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    ) = EtcdLeaderGroupElectionOptions(keyPrefix = prefix, leaderGroupOptions = leaderGroupOptions)

    private fun callRunAsyncIfLeaderExplicit(client: io.etcd.jetcd.Client, lockName: String, action: () -> CompletableFuture<String>) =
        client.runAsyncIfLeader(lockName, singleOptions(), VirtualThreadExecutor, action)

    private fun callRunAsyncIfLeaderDefault(client: io.etcd.jetcd.Client, lockName: String, action: () -> CompletableFuture<String>) =
        client.runAsyncIfLeader(lockName, action = action)

    private fun callRunAsyncIfLeaderGroupExplicit(client: io.etcd.jetcd.Client, lockName: String, action: () -> CompletableFuture<String>) =
        client.runAsyncIfLeaderGroup(lockName, groupOptions(), VirtualThreadExecutor, action)

    private fun callRunAsyncIfLeaderGroupDefault(client: io.etcd.jetcd.Client, lockName: String, action: () -> CompletableFuture<String>) =
        client.runAsyncIfLeaderGroup(lockName, action = action)

    private suspend fun callSuspendRunIfLeaderExplicit(client: io.etcd.jetcd.Client, lockName: String, action: suspend () -> String) =
        client.suspendRunIfLeader(lockName, singleOptions(), action)

    private suspend fun callSuspendRunIfLeaderDefault(client: io.etcd.jetcd.Client, lockName: String, action: suspend () -> String) =
        client.suspendRunIfLeader(lockName, action = action)

    private suspend fun callSuspendRunIfLeaderGroupExplicit(client: io.etcd.jetcd.Client, lockName: String, action: suspend () -> String) =
        client.suspendRunIfLeaderGroup(lockName, groupOptions(), action)

    private suspend fun callSuspendRunIfLeaderGroupDefault(client: io.etcd.jetcd.Client, lockName: String, action: suspend () -> String) =
        client.suspendRunIfLeaderGroup(lockName, action = action)

    private fun callRunVirtualIfLeaderExplicit(client: io.etcd.jetcd.Client, lockName: String, action: () -> String) =
        client.runVirtualIfLeader(lockName, singleOptions(), action)

    private fun callRunVirtualIfLeaderDefault(client: io.etcd.jetcd.Client, lockName: String, action: () -> String) =
        client.runVirtualIfLeader(lockName, action = action)

    private fun callRunVirtualIfLeaderGroupExplicit(client: io.etcd.jetcd.Client, lockName: String, action: () -> String) =
        client.runVirtualIfLeaderGroup(lockName, groupOptions(), action)

    private fun callRunVirtualIfLeaderGroupDefault(client: io.etcd.jetcd.Client, lockName: String, action: () -> String) =
        client.runVirtualIfLeaderGroup(lockName, action = action)

    @Test
    fun `source consumers call every async suspend and virtual facade`() = runSuspendIO {
        newClient().use { client ->
            val calls = AtomicInteger()
            val next: () -> String = { "call-${calls.incrementAndGet()}" }
            val nextAsync: () -> CompletableFuture<String> = { CompletableFuture.completedFuture(next()) }

            callRunAsyncIfLeaderExplicit(client, randomName(), nextAsync).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-1"
            callRunAsyncIfLeaderGroupExplicit(client, randomName(), nextAsync).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-2"
            callSuspendRunIfLeaderExplicit(client, randomName()) { next() } shouldBeEqualTo "call-3"
            callSuspendRunIfLeaderGroupExplicit(client, randomName()) { next() } shouldBeEqualTo "call-4"
            callRunVirtualIfLeaderExplicit(client, randomName(), next).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-5"
            callRunVirtualIfLeaderGroupExplicit(client, randomName(), next).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-6"

            callRunAsyncIfLeaderDefault(client, randomName(), nextAsync).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-7"
            callRunAsyncIfLeaderGroupDefault(client, randomName(), nextAsync).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-8"
            callSuspendRunIfLeaderDefault(client, randomName()) { next() } shouldBeEqualTo "call-9"
            callSuspendRunIfLeaderGroupDefault(client, randomName()) { next() } shouldBeEqualTo "call-10"
            callRunVirtualIfLeaderDefault(client, randomName(), next).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-11"
            callRunVirtualIfLeaderGroupDefault(client, randomName(), next).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-12"

            calls.get() shouldBeEqualTo 12
        }
    }

    @Test
    fun `async facades skip callbacks during single and group contention and can reacquire`() {
        newClient().use { client ->
            val executor = Executors.newFixedThreadPool(2)
            val singlePrefix = "/issue-994/lifecycle-single-${randomName()}"
            val singleLockName = randomName()
            val singleStarted = CountDownLatch(1)
            val singleAction = CompletableFuture<String>()
            val singleHolder = client.runAsyncIfLeader(
                singleLockName,
                options = singleOptions(singlePrefix, LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 5.seconds)),
                executor = executor,
            ) {
                singleStarted.countDown()
                singleAction
            }

            val groupPrefix = "/issue-994/lifecycle-group-${randomName()}"
            val groupLockName = randomName()
            val groupStarted = CountDownLatch(1)
            val groupAction = CompletableFuture<String>()
            val groupHolder = client.runAsyncIfLeaderGroup(
                groupLockName,
                options = groupOptions(
                    groupPrefix,
                    LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 1.seconds, leaseTime = 5.seconds),
                ),
                executor = executor,
            ) {
                groupStarted.countDown()
                groupAction
            }

            try {
                singleStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                groupStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()

                val singleCalls = AtomicInteger()
                client.runAsyncIfLeader(
                    singleLockName,
                    options = singleOptions(
                        singlePrefix,
                        LeaderElectionOptions(waitTime = 150.milliseconds, leaseTime = 5.seconds),
                    ),
                    executor = VirtualThreadExecutor,
                ) {
                    singleCalls.incrementAndGet()
                    CompletableFuture.completedFuture("must-not-run")
                }.get(5, TimeUnit.SECONDS).shouldBeNull()
                singleCalls.get() shouldBeEqualTo 0

                val groupCalls = AtomicInteger()
                client.runAsyncIfLeaderGroup(
                    groupLockName,
                    options = groupOptions(
                        groupPrefix,
                        LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 150.milliseconds, leaseTime = 5.seconds),
                    ),
                    executor = VirtualThreadExecutor,
                ) {
                    groupCalls.incrementAndGet()
                    CompletableFuture.completedFuture("must-not-run")
                }.get(5, TimeUnit.SECONDS).shouldBeNull()
                groupCalls.get() shouldBeEqualTo 0

                singleHolder.cancel(true).shouldBeTrue()
                groupHolder.cancel(true).shouldBeTrue()
                await atMost 5.seconds until { singleAction.isCancelled && groupAction.isCancelled }

                client.runAsyncIfLeader(
                    singleLockName,
                    options = singleOptions(singlePrefix, LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 5.seconds)),
                    executor = VirtualThreadExecutor,
                ) { CompletableFuture.completedFuture("single-reacquired") }
                    .get(5, TimeUnit.SECONDS) shouldBeEqualTo "single-reacquired"
                client.runAsyncIfLeaderGroup(
                    groupLockName,
                    options = groupOptions(
                        groupPrefix,
                        LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 1.seconds, leaseTime = 5.seconds),
                    ),
                    executor = VirtualThreadExecutor,
                ) { CompletableFuture.completedFuture("group-reacquired") }
                    .get(5, TimeUnit.SECONDS) shouldBeEqualTo "group-reacquired"
            } finally {
                singleAction.cancel(true)
                groupAction.cancel(true)
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `async facade callback failure releases the lock`() {
        newClient().use { client ->
            val lockName = randomName()
            val options = singleOptions(
                "/issue-994/failure-${randomName()}",
                LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 5.seconds),
            )
            val failure = IllegalStateException("issue-994 etcd failure")

            val result = client.runAsyncIfLeader(
                lockName,
                options = options,
                executor = VirtualThreadExecutor,
            ) { failedCompletableFutureOf<String>(failure) }
            val thrown = assertFailsWith<ExecutionException> { result.get(5, TimeUnit.SECONDS) }
            thrown.cause shouldBeEqualTo failure

            client.runAsyncIfLeader(
                lockName,
                options = options,
                executor = VirtualThreadExecutor,
            ) { CompletableFuture.completedFuture("reacquired") }
                .get(5, TimeUnit.SECONDS) shouldBeEqualTo "reacquired"
        }
    }

    @Test
    fun `async group facade callback failure releases the slot`() {
        newClient().use { client ->
            val lockName = randomName()
            val options = groupOptions(
                "/issue-994/group-failure-${randomName()}",
                LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 1.seconds, leaseTime = 5.seconds),
            )
            val failure = IllegalStateException("issue-994 etcd group failure")

            val failed = client.runAsyncIfLeaderGroup(
                lockName,
                options = options,
                executor = VirtualThreadExecutor,
            ) { failedCompletableFutureOf<String>(failure) }
            val thrown = assertFailsWith<ExecutionException> { failed.get(5, TimeUnit.SECONDS) }
            thrown.cause shouldBeEqualTo failure

            client.runAsyncIfLeaderGroup(
                lockName,
                options = options,
                executor = VirtualThreadExecutor,
            ) { CompletableFuture.completedFuture("reacquired") }
                .get(5, TimeUnit.SECONDS) shouldBeEqualTo "reacquired"
        }
    }

    @Test
    fun `suspend facade cancellation releases the lock`() = runSuspendIO {
        newClient().use { client ->
            val lockName = randomName()
            val options = singleOptions(
                "/issue-994/suspend-cancel-${randomName()}",
                LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 5.seconds),
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

    @Test
    fun `virtual single facade cancellation interrupts callback and permits reacquisition`() {
        newClient().use { client ->
            val lockName = randomName()
            val options = singleOptions(
                "/issue-994/virtual-cancel-${randomName()}",
                LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 5.seconds),
            )
            val actionStarted = CountDownLatch(1)
            val actionInterrupted = CountDownLatch(1)
            val release = CountDownLatch(1)
            val result = client.runVirtualIfLeader(lockName, options) {
                actionStarted.countDown()
                try {
                    release.await()
                    "should-not-complete"
                } catch (error: InterruptedException) {
                    actionInterrupted.countDown()
                    throw error
                }
            }

            try {
                actionStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                result.cancel(true).shouldBeTrue()
                actionInterrupted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                await atMost 5.seconds until {
                    client.runIfLeader(lockName, options) { "reacquired" } == "reacquired"
                }
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `virtual group facade cancellation interrupts callback and permits slot reuse`() {
        newClient().use { client ->
            val lockName = randomName()
            val options = groupOptions(
                "/issue-994/virtual-group-cancel-${randomName()}",
                LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 1.seconds, leaseTime = 5.seconds),
            )
            val actionStarted = CountDownLatch(1)
            val actionInterrupted = CountDownLatch(1)
            val release = CountDownLatch(1)
            val result = client.runVirtualIfLeaderGroup(lockName, options) {
                actionStarted.countDown()
                try {
                    release.await()
                    "should-not-complete"
                } catch (error: InterruptedException) {
                    actionInterrupted.countDown()
                    throw error
                }
            }

            try {
                actionStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                result.cancel(true).shouldBeTrue()
                actionInterrupted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                await atMost 5.seconds until {
                    client.runIfLeaderGroup(lockName, options) { "slot-reused" } == "slot-reused"
                }
            } finally {
                release.countDown()
            }
        }
    }
}
