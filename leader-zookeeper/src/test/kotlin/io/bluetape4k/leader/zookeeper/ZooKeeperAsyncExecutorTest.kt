package io.bluetape4k.leader.zookeeper

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBe
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.awaitTermination
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.concurrent.get
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.logging.KLogging
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ZooKeeperAsyncExecutorTest: AbstractZooKeeperLeaderTest() {

    companion object: KLogging()

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `single과 group은 동일 single thread executor의 action을 기다릴 수 있다`(group: Boolean) {
        val executor = Executors.newSingleThreadExecutor()
        val name = randomName()
        val action = {
            futureOf(executor) { "done" }
        }
        val result = if (group) {
            ZooKeeperLeaderGroupElector(curator, LeaderGroupElectionOptions(maxLeaders = 1))
                .runAsyncIfLeader(name, executor, action)
        } else {
            ZooKeeperLeaderElector(curator, options = LeaderElectionOptions(waitTime = 100.milliseconds))
                .runAsyncIfLeader(name, executor, action)
        }
        try {
            result.get(3.seconds) shouldBeEqualTo "done"
        } finally {
            result.cancel(true)
            executor.shutdownNow()
            executor.awaitTermination(3.seconds)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `action 제출 거부 뒤 single과 group 모두 즉시 재획득할 수 있다`(group: Boolean) {
        val name = randomName()
        val rejection = RejectedExecutionException("caller executor closed")

        val result = runAsync(group, name, { throw rejection }) {
            error("action must not run")
        }

        val failure = assertFailsWith<ExecutionException> {
            result.get(3.seconds)
        }
        failure.cause shouldBe rejection
        reacquire(group, name) shouldBeEqualTo "reacquired"
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `원래 action 실패를 보존하고 single과 group lease를 해제한다`(group: Boolean) {
        val name = randomName()
        val actionFailure = IllegalArgumentException("action failed")
        val result = runAsync(group, name, { it.run() }) {
            failedCompletableFutureOf(actionFailure)
        }
        val failure = assertFailsWith<ExecutionException> {
            result.get(3.seconds)
        }
        failure.cause shouldBe actionFailure
        reacquire(group, name) shouldBeEqualTo "reacquired"
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `제출 대기 중 취소는 lease를 해제하고 늦은 action을 실행하지 않는다`(group: Boolean) {
        val name = randomName()
        val submitted = CountDownLatch(1)
        val queued = AtomicReference<Runnable>()
        val executor = Executor { command -> queued.set(command); submitted.countDown() }

        val result = runAsync(group, name, executor) {
            error("cancelled action must not run")
        }
        try {
            submitted.await(3.seconds).shouldBeTrue()
            result.cancel(false).shouldBeTrue()

            await atMost 5.seconds withPollInterval 100.milliseconds untilAsserted {
                reacquire(group, name) shouldBeEqualTo "reacquired"
            }
            queued.get().run()
            result.isCancelled.shouldBeTrue()
        } finally {
            result.cancel(true)
        }
    }

    private fun runAsync(
        group: Boolean,
        name: String,
        executor: Executor,
        action: () -> CompletableFuture<String>,
    ): CompletableFuture<String?> = if (group) {
        ZooKeeperLeaderGroupElector(curator, LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 100.milliseconds))
            .runAsyncIfLeader(name, executor, action)
    } else {
        ZooKeeperLeaderElector(curator, options = LeaderElectionOptions(waitTime = 100.milliseconds))
            .runAsyncIfLeader(name, executor, action)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `caller action에 lock handle scope를 전달하고 반환 뒤 제거한다`(group: Boolean) {
        val executor = Executors.newSingleThreadExecutor()
        val name = randomName()
        try {
            val result = runAsync(group, name, executor) {
                AopScopeAccess.peekSyncMatching(name).shouldNotBeNull()
                if (group) {
                    AopScopeAccess.pollCapture().shouldNotBeNull()
                }
                completableFutureOf("done")
            }
            result.get(3.seconds) shouldBeEqualTo "done"

            futureOf(executor) {
                AopScopeAccess.peekSyncMatching(name) == null && AopScopeAccess.pollCapture() == null
            }.get(3.seconds).shouldBeTrue()

        } finally {
            executor.shutdownNow()
            executor.awaitTermination(3.seconds)
        }
    }

    private fun reacquire(group: Boolean, name: String): String? =
        if (group) {
            ZooKeeperLeaderGroupElector(
                curator,
                LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 100.milliseconds)
            ).runIfLeader(name) { "reacquired" }
        } else {
            ZooKeeperLeaderElector(
                curator,
                options = LeaderElectionOptions(waitTime = 100.milliseconds)
            ).runIfLeader(name) { "reacquired" }
        }
}
