package io.bluetape4k.leader.zookeeper

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import org.apache.curator.framework.CuratorFramework
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class InlineBackendFacadeConsumerTest : AbstractZooKeeperLeaderTest() {

    private fun callRunAsyncIfLeaderPathExplicit(
        client: CuratorFramework,
        path: ZooKeeperElectionPath,
        action: () -> CompletableFuture<String>,
    ) = client.runAsyncIfLeader(path, VirtualThreadExecutor, io.bluetape4k.leader.LeaderElectionOptions.Default, action)

    private fun callRunAsyncIfLeaderPathDefault(
        client: CuratorFramework,
        path: ZooKeeperElectionPath,
        action: () -> CompletableFuture<String>,
    ) = client.runAsyncIfLeader(path, action = action)

    private fun callRunAsyncIfLeaderNameExplicit(
        client: CuratorFramework,
        lockName: String,
        action: () -> CompletableFuture<String>,
    ) = client.runAsyncIfLeader(
        lockName,
        VirtualThreadExecutor,
        ZooKeeperLeaderElector.DEFAULT_BASE_PATH,
        io.bluetape4k.leader.LeaderElectionOptions.Default,
        action,
    )

    private fun callRunAsyncIfLeaderNameDefault(
        client: CuratorFramework,
        lockName: String,
        action: () -> CompletableFuture<String>,
    ) = client.runAsyncIfLeader(lockName, action = action)

    private fun callRunAsyncIfLeaderGroupPathExplicit(
        client: CuratorFramework,
        path: ZooKeeperElectionPath,
        action: () -> CompletableFuture<String>,
    ) = client.runAsyncIfLeaderGroup(path, VirtualThreadExecutor, io.bluetape4k.leader.LeaderGroupElectionOptions.Default, action)

    private fun callRunAsyncIfLeaderGroupPathDefault(
        client: CuratorFramework,
        path: ZooKeeperElectionPath,
        action: () -> CompletableFuture<String>,
    ) = client.runAsyncIfLeaderGroup(path, action = action)

    private fun callRunAsyncIfLeaderGroupNameExplicit(
        client: CuratorFramework,
        lockName: String,
        action: () -> CompletableFuture<String>,
    ) = client.runAsyncIfLeaderGroup(
        lockName,
        VirtualThreadExecutor,
        io.bluetape4k.leader.LeaderGroupElectionOptions.Default,
        ZooKeeperLeaderGroupElector.DEFAULT_BASE_PATH,
        action,
    )

    private fun callRunAsyncIfLeaderGroupNameDefault(
        client: CuratorFramework,
        lockName: String,
        action: () -> CompletableFuture<String>,
    ) = client.runAsyncIfLeaderGroup(lockName, action = action)

    @Test
    fun `source consumers call both async overloads for single and group facades`() {
        val calls = AtomicInteger()
        val next: () -> CompletableFuture<String> = {
            CompletableFuture.completedFuture("call-${calls.incrementAndGet()}")
        }
        val singlePath = ZooKeeperElectionPath.single(randomName())
        val groupPath = ZooKeeperElectionPath.group(randomName())

        callRunAsyncIfLeaderPathExplicit(curator, singlePath, next).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-1"
        callRunAsyncIfLeaderNameExplicit(curator, randomName(), next).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-2"
        callRunAsyncIfLeaderGroupPathExplicit(curator, groupPath, next).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-3"
        callRunAsyncIfLeaderGroupNameExplicit(curator, randomName(), next).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-4"

        callRunAsyncIfLeaderPathDefault(curator, ZooKeeperElectionPath.single(randomName()), next)
            .get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-5"
        callRunAsyncIfLeaderNameDefault(curator, randomName(), next).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-6"
        callRunAsyncIfLeaderGroupPathDefault(curator, ZooKeeperElectionPath.group(randomName()), next)
            .get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-7"
        callRunAsyncIfLeaderGroupNameDefault(curator, randomName(), next).get(10, TimeUnit.SECONDS) shouldBeEqualTo "call-8"

        calls.get() shouldBeEqualTo 8
    }

    @Test
    fun `async facades skip callbacks during single and group contention`() {
        val client = curator
        val executor = Executors.newSingleThreadExecutor()
        try {
            val singleName = randomName()
            val singleStarted = CountDownLatch(1)
            val singleRelease = CountDownLatch(1)
            val singleHolder = executor.submit {
                ZooKeeperLeaderElector(
                    client,
                    options = LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 5.seconds),
                ).runIfLeader(singleName) {
                    singleStarted.countDown()
                    singleRelease.await()
                    "holder"
                }
            }

            singleStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
            val singleCalls = AtomicInteger()
            client.runAsyncIfLeader(
                singleName,
                executor = VirtualThreadExecutor,
                options = LeaderElectionOptions(waitTime = 150.milliseconds, leaseTime = 5.seconds),
            ) {
                singleCalls.incrementAndGet()
                CompletableFuture.completedFuture("must-not-run")
            }.get(5, TimeUnit.SECONDS).shouldBeNull()
            singleCalls.get() shouldBeEqualTo 0
            singleRelease.countDown()
            singleHolder.get(5, TimeUnit.SECONDS)

            val groupName = randomName()
            val groupStarted = CountDownLatch(1)
            val groupRelease = CountDownLatch(1)
            val groupHolder = executor.submit {
                ZooKeeperLeaderGroupElector(
                    client,
                    LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 1.seconds, leaseTime = 5.seconds),
                ).runIfLeader(groupName) {
                    groupStarted.countDown()
                    groupRelease.await()
                    "holder"
                }
            }

            groupStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
            val groupCalls = AtomicInteger()
            client.runAsyncIfLeaderGroup(
                groupName,
                executor = VirtualThreadExecutor,
                options = LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 150.milliseconds, leaseTime = 5.seconds),
            ) {
                groupCalls.incrementAndGet()
                CompletableFuture.completedFuture("must-not-run")
            }.get(5, TimeUnit.SECONDS).shouldBeNull()
            groupCalls.get() shouldBeEqualTo 0
            groupRelease.countDown()
            groupHolder.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `async facade failure and cancellation release the lock`() {
        val lockName = randomName()
        val options = LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 5.seconds)
        val failure = IllegalStateException("issue-994 zookeeper failure")
        val failed = curator.runAsyncIfLeader(
            lockName,
            executor = VirtualThreadExecutor,
            options = options,
        ) { failedCompletableFutureOf<String>(failure) }
        val thrown = assertFailsWith<ExecutionException> { failed.get(5, TimeUnit.SECONDS) }
        thrown.cause shouldBeEqualTo failure

        curator.runAsyncIfLeader(lockName, executor = VirtualThreadExecutor, options = options) {
            CompletableFuture.completedFuture("after-failure")
        }.get(5, TimeUnit.SECONDS) shouldBeEqualTo "after-failure"

        val actionStarted = CountDownLatch(1)
        val action = CompletableFuture<String>()
        val result = curator.runAsyncIfLeader(lockName, executor = VirtualThreadExecutor, options = options) {
            actionStarted.countDown()
            action
        }
        actionStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
        result.cancel(false).shouldBeTrue()
        await atMost 5.seconds until { action.isCancelled }
        curator.runAsyncIfLeader(lockName, executor = VirtualThreadExecutor, options = options) {
            CompletableFuture.completedFuture("after-cancel")
        }.get(5, TimeUnit.SECONDS) shouldBeEqualTo "after-cancel"
        action.cancel(true)
    }

    @Test
    fun `async group facade failure and cancellation release the slot`() {
        val failure = IllegalStateException("issue-994 zookeeper group failure")
        val failureName = randomName()
        val failureOptions = LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 1.seconds, leaseTime = 5.seconds)
        val failed = curator.runAsyncIfLeaderGroup(
            failureName,
            executor = VirtualThreadExecutor,
            options = failureOptions,
        ) { failedCompletableFutureOf<String>(failure) }
        val thrown = assertFailsWith<ExecutionException> { failed.get(5, TimeUnit.SECONDS) }
        thrown.cause shouldBeEqualTo failure

        curator.runAsyncIfLeaderGroup(
            failureName,
            executor = VirtualThreadExecutor,
            options = failureOptions,
        ) { CompletableFuture.completedFuture("after-failure") }
            .get(5, TimeUnit.SECONDS) shouldBeEqualTo "after-failure"

        val lockName = randomName()
        val actionStarted = CountDownLatch(1)
        val action = CompletableFuture<String>()
        val result = curator.runAsyncIfLeaderGroup(
            lockName,
            executor = VirtualThreadExecutor,
            options = failureOptions,
        ) {
            actionStarted.countDown()
            action
        }
        actionStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
        result.cancel(false).shouldBeTrue()
        await atMost 5.seconds until { action.isCancelled }
        curator.runAsyncIfLeaderGroup(
            lockName,
            executor = VirtualThreadExecutor,
            options = failureOptions,
        ) { CompletableFuture.completedFuture("after-cancel") }
            .get(5, TimeUnit.SECONDS) shouldBeEqualTo "after-cancel"
        action.cancel(true)
    }
}
