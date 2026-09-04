package io.bluetape4k.leader.redisson

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderSlot
import org.junit.jupiter.api.Test
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RedissonExecutorRejectionLifecycleTest: AbstractRedissonLeaderTest() {

    @Test
    fun `single result 취소를 action과 lock lifecycle에 전파한다`() {
        val lockName = randomName()
        val election = RedissonLeaderElector(redissonClient)
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val actionStarted = CountDownLatch(1)
        val actionFuture = CompletableFuture<String>()

        try {
            val result = election.runAsyncIfLeaderResult(LeaderSlot(lockName, "redisson-cancel"), executor) {
                actionStarted.countDown()
                actionFuture
            }

            actionStarted.await(2, TimeUnit.SECONDS) shouldBeEqualTo true
            result.cancel(false) shouldBeEqualTo true
            actionFuture.isCancelled shouldBeEqualTo true
            await.atMost(2.seconds).untilAsserted {
                election.runIfLeader(lockName) { "recovered" } shouldBeEqualTo "recovered"
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `group result 취소를 action과 permit lifecycle에 전파한다`() {
        val lockName = randomName()
        val election = RedissonLeaderGroupElector(
            redissonClient,
            LeaderGroupElectionOptions(maxLeaders = 1),
        )
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val actionStarted = CountDownLatch(1)
        val actionFuture = CompletableFuture<String>()

        try {
            val result = election.runAsyncIfLeaderResult(LeaderSlot(lockName, "redisson-group-cancel"), executor) {
                actionStarted.countDown()
                actionFuture
            }

            actionStarted.await(2, TimeUnit.SECONDS) shouldBeEqualTo true
            result.cancel(false) shouldBeEqualTo true
            actionFuture.isCancelled shouldBeEqualTo true
            await.atMost(2.seconds).untilAsserted {
                election.runIfLeader(lockName) { "recovered" } shouldBeEqualTo "recovered"
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `single async executor 거부 후 획득한 lock을 정리한다`() {
        val lockName = randomName()
        val election = RedissonLeaderElector(
            redissonClient,
            LeaderElectionOptions(waitTime = 100.milliseconds, leaseTime = 10.seconds),
        )
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
            val primed = CountDownLatch(1)
            executor.execute { primed.countDown() }
            primed.await(2, TimeUnit.SECONDS) shouldBeEqualTo true

            val rejected = election.runAsyncIfLeader(lockName, executor) {
                actionInvoked.set(true)
                CompletableFuture.completedFuture("must-not-run")
            }
            val failure = assertFailsWith<CompletionException> { rejected.join() }

            failure.cause.shouldBeInstanceOf<RejectedExecutionException>()
            submissions.get() shouldBeEqualTo 2
            actionInvoked.get() shouldBeEqualTo false
            CompletableFuture.supplyAsync {
                election.runIfLeader(lockName) { "recovered" }
            }.get(2, TimeUnit.SECONDS) shouldBeEqualTo "recovered"
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun `group async executor 거부 후 획득한 permit을 정리한다`() {
        val lockName = randomName()
        val election = RedissonLeaderGroupElector(
            redissonClient,
            LeaderGroupElectionOptions(
                maxLeaders = 1,
                waitTime = 100.milliseconds,
                leaseTime = 10.seconds,
            ),
        )
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
            val primed = CountDownLatch(1)
            executor.execute { primed.countDown() }
            primed.await(2, TimeUnit.SECONDS) shouldBeEqualTo true

            val rejected = election.runAsyncIfLeader(lockName, executor) {
                actionInvoked.set(true)
                CompletableFuture.completedFuture("must-not-run")
            }
            val failure = assertFailsWith<CompletionException> { rejected.join() }

            failure.cause.shouldBeInstanceOf<RejectedExecutionException>()
            submissions.get() shouldBeEqualTo 2
            actionInvoked.get() shouldBeEqualTo false
            election.runIfLeader(lockName) { "recovered" } shouldBeEqualTo "recovered"
        } finally {
            worker.shutdownNow()
        }
    }
}
