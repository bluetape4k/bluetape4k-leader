package io.bluetape4k.leader.etcd

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LockExtender
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class EtcdAsyncLeaderElectorIntegrationTest: AbstractEtcdLeaderTest() {

    @Test
    fun `single cancellation reaches action and same lock can be reacquired`() {
        newClient().use { client ->
            val executor = Executors.newSingleThreadExecutor()
            val actionStarted = CountDownLatch(1)
            val actionFuture = CompletableFuture<String>()
            val elector = EtcdLeaderElector(
                client,
                EtcdLeaderElectionOptions(
                    leaderOptions = LeaderElectionOptions(
                        waitTime = 2.seconds,
                        leaseTime = 10.seconds,
                        autoExtend = true,
                    ),
                    keyPrefix = "/bluetape4k/leader/test/${randomName()}",
                ),
            )
            val lockName = randomName()

            try {
                val result = elector.runAsyncIfLeader(lockName, executor) {
                    actionStarted.countDown()
                    actionFuture
                }

                actionStarted.await(10, TimeUnit.SECONDS).shouldBeTrue()
                result.cancel(true).shouldBeTrue()
                result.isCancelled.shouldBeTrue()
                actionFuture.isCancelled.shouldBeTrue()
                elector.runIfLeader(lockName) { "reacquired" } shouldBeEqualTo "reacquired"
            } finally {
                actionFuture.cancel(true)
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `group cancellation reaches action and same slot can be reacquired`() {
        newClient().use { client ->
            val executor = Executors.newSingleThreadExecutor()
            val actionStarted = CountDownLatch(1)
            val actionFuture = CompletableFuture<String>()
            val elector = EtcdLeaderGroupElector(
                client,
                EtcdLeaderGroupElectionOptions(
                    leaderGroupOptions = LeaderGroupElectionOptions(
                        maxLeaders = 1,
                        waitTime = 2.seconds,
                        leaseTime = 10.seconds,
                    ),
                    keyPrefix = "/bluetape4k/leader/test/${randomName()}",
                ),
            )
            val lockName = randomName()

            try {
                val result = elector.runAsyncIfLeader(lockName, executor) {
                    actionStarted.countDown()
                    actionFuture
                }

                actionStarted.await(10, TimeUnit.SECONDS).shouldBeTrue()
                result.cancel(true).shouldBeTrue()
                result.isCancelled.shouldBeTrue()
                actionFuture.isCancelled.shouldBeTrue()
                elector.runIfLeader(lockName) { "reacquired" } shouldBeEqualTo "reacquired"
            } finally {
                actionFuture.cancel(true)
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `single async contention returns null without invoking action`() {
        newClient().use { client ->
            val executor = Executors.newFixedThreadPool(2)
            val keyPrefix = "/bluetape4k/leader/test/${randomName()}"
            val holder = EtcdLeaderElector(
                client,
                EtcdLeaderElectionOptions(
                    leaderOptions = LeaderElectionOptions(waitTime = 2.seconds, leaseTime = 10.seconds),
                    keyPrefix = keyPrefix,
                ),
            )
            val contender = EtcdLeaderElector(
                client,
                EtcdLeaderElectionOptions(
                    leaderOptions = LeaderElectionOptions(waitTime = 200.milliseconds, leaseTime = 10.seconds),
                    keyPrefix = keyPrefix,
                ),
            )
            val lockName = randomName()
            val holderStarted = CountDownLatch(1)
            val holderAction = CompletableFuture<String>()
            val contenderInvoked = AtomicBoolean()

            try {
                val holderResult = holder.runAsyncIfLeader(lockName, executor) {
                    holderStarted.countDown()
                    holderAction
                }
                holderStarted.await(10, TimeUnit.SECONDS).shouldBeTrue()

                contender.runAsyncIfLeader(lockName, executor) {
                    contenderInvoked.set(true)
                    CompletableFuture.completedFuture("should-not-run")
                }.get(10, TimeUnit.SECONDS) shouldBeEqualTo null
                contenderInvoked.get() shouldBeEqualTo false

                holderAction.complete("holder")
                holderResult.get(10, TimeUnit.SECONDS) shouldBeEqualTo "holder"
            } finally {
                holderAction.cancel(true)
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `group async contention returns null without invoking action`() {
        newClient().use { client ->
            val executor = Executors.newFixedThreadPool(2)
            val keyPrefix = "/bluetape4k/leader/test/${randomName()}"
            val holder = EtcdLeaderGroupElector(
                client,
                EtcdLeaderGroupElectionOptions(
                    leaderGroupOptions = LeaderGroupElectionOptions(
                        maxLeaders = 1,
                        waitTime = 2.seconds,
                        leaseTime = 10.seconds,
                    ),
                    keyPrefix = keyPrefix,
                ),
            )
            val contender = EtcdLeaderGroupElector(
                client,
                EtcdLeaderGroupElectionOptions(
                    leaderGroupOptions = LeaderGroupElectionOptions(
                        maxLeaders = 1,
                        waitTime = 200.milliseconds,
                        leaseTime = 10.seconds,
                    ),
                    keyPrefix = keyPrefix,
                ),
            )
            val lockName = randomName()
            val holderStarted = CountDownLatch(1)
            val holderAction = CompletableFuture<String>()
            val contenderInvoked = AtomicBoolean()

            try {
                val holderResult = holder.runAsyncIfLeader(lockName, executor) {
                    holderStarted.countDown()
                    holderAction
                }
                holderStarted.await(10, TimeUnit.SECONDS).shouldBeTrue()

                contender.runAsyncIfLeader(lockName, executor) {
                    contenderInvoked.set(true)
                    CompletableFuture.completedFuture("should-not-run")
                }.get(10, TimeUnit.SECONDS) shouldBeEqualTo null
                contenderInvoked.get() shouldBeEqualTo false

                holderAction.complete("holder")
                holderResult.get(10, TimeUnit.SECONDS) shouldBeEqualTo "holder"
            } finally {
                holderAction.cancel(true)
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `single async action supplier can extend active lock`() {
        newClient().use { client ->
            val executor = Executors.newSingleThreadExecutor()
            val elector = EtcdLeaderElector(
                client,
                EtcdLeaderElectionOptions(
                    leaderOptions = LeaderElectionOptions(waitTime = 2.seconds, leaseTime = 10.seconds),
                    keyPrefix = "/bluetape4k/leader/test/${randomName()}",
                ),
            )

            try {
                elector.runAsyncIfLeader(randomName(), executor) {
                    CompletableFuture.completedFuture(LockExtender.extendActiveLock(10.seconds))
                }.get(10, TimeUnit.SECONDS) shouldBeEqualTo true
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `group async action supplier can extend active lock`() {
        newClient().use { client ->
            val executor = Executors.newSingleThreadExecutor()
            val elector = EtcdLeaderGroupElector(
                client,
                EtcdLeaderGroupElectionOptions(
                    leaderGroupOptions = LeaderGroupElectionOptions(
                        maxLeaders = 1,
                        waitTime = 2.seconds,
                        leaseTime = 10.seconds,
                    ),
                    keyPrefix = "/bluetape4k/leader/test/${randomName()}",
                ),
            )

            try {
                elector.runAsyncIfLeader(randomName(), executor) {
                    CompletableFuture.completedFuture(LockExtender.extendActiveLock(10.seconds))
                }.get(10, TimeUnit.SECONDS) shouldBeEqualTo true
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
