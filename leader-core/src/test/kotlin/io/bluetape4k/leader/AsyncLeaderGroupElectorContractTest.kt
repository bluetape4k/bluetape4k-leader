package io.bluetape4k.leader

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.local.LocalAsyncLeaderGroupElector
import io.bluetape4k.leader.local.LocalLeaderGroupElector
import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeLessOrEqualTo
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.random.Random

/**
 * [AsyncLeaderGroupElector] 인터페이스 계약을 검증하는 테스트입니다.
 *
 * [LocalAsyncLeaderGroupElector]과 [LocalLeaderGroupElector] 구현체를 통해
 * [AsyncLeaderGroupElector] 계약을 검증합니다.
 */
class AsyncLeaderGroupElectorContractTest {

    companion object: KLogging()

    private val maxLeaders = 3
    private val options = LeaderGroupElectionOptions(maxLeaders)

    private fun randomLockName() = "lock-${Base58.randomString(8)}"

    // ── 기본 동작 ──────────────────────────────────────────────────────────

    @Test
    fun `runAsyncIfLeader - 리더 획득 성공 시 CompletableFuture action 을 실행한다`() {
        val election: AsyncLeaderGroupElector = LocalAsyncLeaderGroupElector(options)
        val result = election.runAsyncIfLeader(randomLockName()) {
            CompletableFuture.completedFuture("group-ok")
        }.join()
        result shouldBeEqualTo "group-ok"
    }

    @Test
    fun `runAsyncIfLeader - 서로 다른 lockName 은 독립적인 슬롯 풀을 가진다`() {
        val election: AsyncLeaderGroupElector = LocalAsyncLeaderGroupElector(options)
        val f1 = election.runAsyncIfLeader(randomLockName()) { CompletableFuture.completedFuture("a") }
        val f2 = election.runAsyncIfLeader(randomLockName()) { CompletableFuture.completedFuture("b") }

        f1.join() shouldBeEqualTo "a"
        f2.join() shouldBeEqualTo "b"
    }

    @Test
    fun `runAsyncIfLeader - action 실패 후에도 슬롯이 반환되어 다음 호출이 성공한다`() {
        val election: AsyncLeaderGroupElector = LocalAsyncLeaderGroupElector(options)
        val lockName = randomLockName()
        runCatching {
            election.runAsyncIfLeader(lockName) {
                CompletableFuture.failedFuture<Unit>(RuntimeException("실패"))
            }.join()
        }

        val result = election.runAsyncIfLeader(lockName) {
            CompletableFuture.completedFuture("복구")
        }.join()
        result shouldBeEqualTo "복구"
    }

    // ── 상태 조회 (LeaderGroupElectionState 상속) ─────────────────────────

    @Test
    fun `maxLeaders - AsyncLeaderGroupElector 의 maxLeaders 가 올바르다`() {
        val election: AsyncLeaderGroupElector = LocalAsyncLeaderGroupElector(options)
        election.maxLeaders shouldBeEqualTo maxLeaders
    }

    @Test
    fun `state - 초기 상태는 isEmpty=true, isFull=false 이다`() {
        val election: AsyncLeaderGroupElector = LocalAsyncLeaderGroupElector(options)
        val lockName = randomLockName()
        val state = election.state(lockName)

        state.isEmpty.shouldBeTrue()
        state.isFull.shouldBeFalse()
        state.activeCount shouldBeEqualTo 0
        state.availableSlots shouldBeEqualTo maxLeaders
    }

    @Test
    fun `state - maxLeaders 개 슬롯 모두 점유 시 isFull=true 이다`() {
        val election = LocalAsyncLeaderGroupElector(options)
        val lockName = randomLockName()
        val startLatch = CountDownLatch(maxLeaders)
        val holdLatch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(maxLeaders)

        repeat(maxLeaders) {
            executor.submit {
                election.runAsyncIfLeader(lockName) {
                    CompletableFuture.runAsync {
                        startLatch.countDown()
                        holdLatch.await()
                    }
                }.join()
            }
        }
        startLatch.await(2, TimeUnit.SECONDS)

        election.state(lockName).isFull.shouldBeTrue()
        election.availableSlots(lockName) shouldBeEqualTo 0

        holdLatch.countDown()
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)
    }

    // ── 동시 실행 제한 ────────────────────────────────────────────────────

    @Test
    fun `동시 실행 중인 리더 수가 maxLeaders 를 초과하지 않는다`() {
        val election: AsyncLeaderGroupElector = LocalAsyncLeaderGroupElector(options)
        val lockName = randomLockName()
        val currentConcurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)
        val numTasks = maxLeaders * 4
        val futures = (1..numTasks).map {
            election.runAsyncIfLeader(lockName) {
                CompletableFuture.supplyAsync {
                    val current = currentConcurrent.incrementAndGet()
                    peakConcurrent.updateAndGet { max(it, current) }
                    Thread.sleep(Random.nextLong(5, 15))
                    currentConcurrent.decrementAndGet()
                }
            }
        }
        CompletableFuture.allOf(*futures.toTypedArray()).join()

        peakConcurrent.get() shouldBeLessOrEqualTo maxLeaders
    }

    // ── LocalLeaderGroupElector 도 AsyncLeaderGroupElector 계약을 준수한다 ──

    @Test
    fun `LocalLeaderGroupElector 은 AsyncLeaderGroupElector 계약을 준수한다`() {
        val election: AsyncLeaderGroupElector = LocalLeaderGroupElector(options)
        val result = election.runAsyncIfLeader(randomLockName()) {
            CompletableFuture.completedFuture(42)
        }.join()
        result shouldBeEqualTo 42
    }
}
