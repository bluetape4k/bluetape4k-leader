package io.bluetape4k.leader.zookeeper

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeTrue
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.AopScopeAccess
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ZooKeeperAsyncExecutorTest : AbstractZooKeeperLeaderTest() {

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `single과 group은 동일 single thread executor의 action을 기다릴 수 있다`(group: Boolean) {
        val executor = Executors.newSingleThreadExecutor()
        val name = randomName()
        val action = {
            CompletableFuture.supplyAsync({ "done" }, executor)
        }
        val result = if (group) {
            ZooKeeperLeaderGroupElector(curator, LeaderGroupElectionOptions(maxLeaders = 1))
                .runAsyncIfLeader(name, executor, action)
        } else {
            ZooKeeperLeaderElector(curator, options = LeaderElectionOptions(waitTime = 100.milliseconds))
                .runAsyncIfLeader(name, executor, action)
        }
        try {
            result.get(3, TimeUnit.SECONDS) shouldBeEqualTo "done"
        } finally {
            result.cancel(true)
            executor.shutdownNow()
            executor.awaitTermination(3, TimeUnit.SECONDS)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `action 제출 거부 뒤 single과 group 모두 즉시 재획득할 수 있다`(group: Boolean) {
        val name = randomName()
        val rejection = RejectedExecutionException("caller executor closed")
        val result = runAsync(group, name, Executor { throw rejection }) { error("action must not run") }
        val failure = assertFailsWith<ExecutionException> { result.get(3, TimeUnit.SECONDS) }
        (failure.cause === rejection).shouldBeTrue()
        reacquire(group, name) shouldBeEqualTo "reacquired"
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `원래 action 실패를 보존하고 single과 group lease를 해제한다`(group: Boolean) {
        val name = randomName()
        val actionFailure = IllegalArgumentException("action failed")
        val result = runAsync(group, name, Executor { it.run() }) {
            CompletableFuture.failedFuture(actionFailure)
        }
        val failure = assertFailsWith<ExecutionException> { result.get(3, TimeUnit.SECONDS) }
        (failure.cause === actionFailure).shouldBeTrue()
        reacquire(group, name) shouldBeEqualTo "reacquired"
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `제출 대기 중 취소는 lease를 해제하고 늦은 action을 실행하지 않는다`(group: Boolean) {
        val name = randomName()
        val submitted = CountDownLatch(1)
        val queued = AtomicReference<Runnable>()
        val executor = Executor { command -> queued.set(command); submitted.countDown() }
        val result = runAsync(group, name, executor) { error("cancelled action must not run") }
        try {
            submitted.await(3, TimeUnit.SECONDS).shouldBeTrue()
            result.cancel(false).shouldBeTrue()
            await.atMost(5.seconds).untilAsserted {
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
                (AopScopeAccess.peekSyncMatching(name) != null).shouldBeTrue()
                if (group) (AopScopeAccess.pollCapture() != null).shouldBeTrue()
                CompletableFuture.completedFuture("done")
            }
            result.get(3, TimeUnit.SECONDS) shouldBeEqualTo "done"
            CompletableFuture.supplyAsync({
                AopScopeAccess.peekSyncMatching(name) == null && AopScopeAccess.pollCapture() == null
            }, executor).get(3, TimeUnit.SECONDS).shouldBeTrue()
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(3, TimeUnit.SECONDS)
        }
    }

    private fun reacquire(group: Boolean, name: String): String? = if (group) {
        ZooKeeperLeaderGroupElector(curator, LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 100.milliseconds))
            .runIfLeader(name) { "reacquired" }
    } else {
        ZooKeeperLeaderElector(curator, options = LeaderElectionOptions(waitTime = 100.milliseconds))
            .runIfLeader(name) { "reacquired" }
    }
}
