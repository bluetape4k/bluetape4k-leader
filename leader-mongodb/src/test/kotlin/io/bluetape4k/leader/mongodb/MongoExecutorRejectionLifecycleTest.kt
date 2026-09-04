package io.bluetape4k.leader.mongodb

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import org.junit.jupiter.api.Test
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

class MongoExecutorRejectionLifecycleTest: AbstractMongoLeaderTest() {

    @Test
    fun `single async executor 거부 후 획득한 lock을 정리한다`() {
        val lockName = randomName()
        val election = MongoLeaderElector(
            lockCollection,
            MongoLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(waitTime = 100.milliseconds, leaseTime = 10.seconds),
            ),
        )
        val actionInvoked = AtomicBoolean()
        val rejectingExecutor = rejectingSecondSubmissionExecutor()

        try {
            rejectingExecutor.prime()

            val rejected = election.runAsyncIfLeader(lockName, rejectingExecutor.executor) {
                actionInvoked.set(true)
                CompletableFuture.completedFuture("must-not-run")
            }
            val failure = assertFailsWith<CompletionException> { rejected.join() }

            failure.cause.shouldBeInstanceOf<RejectedExecutionException>()
            rejectingExecutor.submissions.get() shouldBeEqualTo 2
            actionInvoked.get() shouldBeEqualTo false
            election.runIfLeader(lockName) { "recovered" } shouldBeEqualTo "recovered"
        } finally {
            rejectingExecutor.close()
        }
    }

    @Test
    fun `group async executor 거부 후 획득한 slot을 정리한다`() {
        val lockName = randomName()
        val election = MongoLeaderGroupElector(
            groupLockCollection,
            MongoLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(
                    maxLeaders = 1,
                    waitTime = 100.milliseconds,
                    leaseTime = 10.seconds,
                ),
            ),
        )
        val actionInvoked = AtomicBoolean()
        val rejectingExecutor = rejectingSecondSubmissionExecutor()

        try {
            rejectingExecutor.prime()

            val rejected = election.runAsyncIfLeader(lockName, rejectingExecutor.executor) {
                actionInvoked.set(true)
                CompletableFuture.completedFuture("must-not-run")
            }
            val failure = assertFailsWith<CompletionException> { rejected.join() }

            failure.cause.shouldBeInstanceOf<RejectedExecutionException>()
            rejectingExecutor.submissions.get() shouldBeEqualTo 2
            actionInvoked.get() shouldBeEqualTo false
            election.runIfLeader(lockName) { "recovered" } shouldBeEqualTo "recovered"
        } finally {
            rejectingExecutor.close()
        }
    }

    private fun rejectingSecondSubmissionExecutor(): RejectingExecutor {
        val worker = Executors.newSingleThreadExecutor()
        val submissions = AtomicInteger()
        val executor = Executor { command ->
            if (submissions.incrementAndGet() == 1) {
                worker.execute(command)
            } else {
                throw RejectedExecutionException("second submission rejected")
            }
        }
        return RejectingExecutor(executor, worker, submissions)
    }

    private class RejectingExecutor(
        val executor: Executor,
        private val worker: java.util.concurrent.ExecutorService,
        val submissions: AtomicInteger,
    ) : AutoCloseable {

        fun prime() {
            val primed = CountDownLatch(1)
            executor.execute { primed.countDown() }
            primed.await(2, TimeUnit.SECONDS) shouldBeEqualTo true
        }

        override fun close() {
            worker.shutdownNow()
        }
    }
}
