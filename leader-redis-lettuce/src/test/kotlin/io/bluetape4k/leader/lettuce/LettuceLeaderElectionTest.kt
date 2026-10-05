package io.bluetape4k.leader.lettuce

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.concurrent.get
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.lettuce.lock.LettuceLock
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LettuceLeaderElectionTest: AbstractLettuceLeaderTest() {

    companion object: KLogging()

    private val options = LeaderElectionOptions(waitTime = 2.seconds, 10.seconds)

    private lateinit var election: LettuceLeaderElector
    private lateinit var lockName: String

    @BeforeEach
    fun setup() {
        election = LettuceLeaderElector(connection, options)
        lockName = randomName()
    }

    // =========================================================================
    // 동기 API
    // =========================================================================

    @Test
    fun `리더 선출 성공 시 action 실행`() {
        val result = election.runIfLeader(lockName) { "done" }
        result shouldBeEqualTo "done"
    }

    @Test
    fun `리더 선출 - 여러 번 순차 실행 가능`() {
        val result1 = election.runIfLeader(lockName) { 1 }
        val result2 = election.runIfLeader(lockName) { 2 }
        result1 shouldBeEqualTo 1
        result2 shouldBeEqualTo 2
    }

    @Test
    fun `리더 선출 - 빠른 종료 시 minLeaseTime 동안 Redis TTL 로 락을 보존한다`() {
        val el = LettuceLeaderElector(
            connection,
            LeaderElectionOptions(
                waitTime = 100.milliseconds,
                leaseTime = 2.seconds,
                minLeaseTime = 300.milliseconds,
            )
        )

        el.runIfLeader(lockName) { "done" } shouldBeEqualTo "done"
        el.runIfLeader(lockName) { "too-early" }.shouldBeNull()

        Thread.sleep(300)
        await atMost 5.seconds withPollInterval 100.milliseconds untilAsserted {
            el.runIfLeader(lockName) { "after-min" } shouldBeEqualTo "after-min"
        }
    }

    @Test
    fun `autoExtend - leaseTime 을 초과하는 action 실행 중 contender 는 획득하지 못한다`() {
        val el = LettuceLeaderElector(
            connection,
            LeaderElectionOptions(
                waitTime = 100.milliseconds,
                leaseTime = 250.milliseconds,
                autoExtend = true,
            )
        )
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

        try {
            val holder = executor.submit<String?> {
                el.runIfLeader(lockName) {
                    started.countDown()
                    release.await(1.seconds)
                    "holder"
                }
            }

            started.await(1.seconds).shouldBeTrue()
            Thread.sleep(450)

            el.runIfLeader(lockName) { "contender" }.shouldBeNull()

            release.countDown()
            holder.get(2.seconds) shouldBeEqualTo "holder"

            el.runIfLeader(lockName) { "after-release" } shouldBeEqualTo "after-release"
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `리더 선출 - action 예외 발생 시 예외 전파`() {
        assertFailsWith<LeaderElectionException> {
            election.runIfLeader(lockName) { throw LeaderElectionException("오류") }
        }
    }

    @Test
    fun `runIfLeaderResult - action 실패는 ActionFailed 로 분류한다`() {
        val failure = LeaderElectionException("result 오류")

        val result = election.runIfLeaderResult(LeaderSlot(lockName, "lettuce-node")) {
            throw failure
        }

        result.shouldBeInstanceOf<LeaderRunResult.ActionFailed>()
        result.cause shouldBeEqualTo failure
    }

    @Test
    fun `runIfLeaderResult - CancellationException 은 ActionFailed 로 감싸지 않고 재전파한다`() {
        val cancellation = CancellationException("lettuce-cancelled")

        val thrown = assertFailsWith<CancellationException> {
            election.runIfLeaderResult<Any?>(LeaderSlot(lockName, "lettuce-node")) {
                throw cancellation
            }
        }

        thrown shouldBeEqualTo cancellation
    }

    @Test
    fun `리더 선출 - action 예외 후 락 해제되어 재선출 가능`() {
        assertFailsWith<LeaderElectionException> {
            election.runIfLeader(lockName) { throw LeaderElectionException("오류") }
        }
        val result = election.runIfLeader(lockName) { "recovered" }
        result shouldBeEqualTo "recovered"
    }

    // =========================================================================
    // 비동기 API
    // =========================================================================

    @Test
    fun `비동기 리더 선출 성공`() {
        val future = election.runAsyncIfLeader(lockName) {
            completableFutureOf("async-done")
        }
        future.get() shouldBeEqualTo "async-done"
    }

    @Test
    fun `비동기 리더 선출 - 여러 번 순차 실행 가능`() {
        val r1 = election.runAsyncIfLeader(lockName) {
            completableFutureOf(1)
        }.get()

        val r2 = election.runAsyncIfLeader(lockName) {
            completableFutureOf(2)
        }.get()

        r1 shouldBeEqualTo 1
        r2 shouldBeEqualTo 2
    }

    @Test
    fun `비동기 리더 선출 - 경합 retry 대기는 executor thread 를 점유하지 않는다`() {
        val holderLock = LettuceLock(connection, lockName)
        holderLock.tryLock(waitTime = 100.milliseconds, leaseTime = 2.seconds).shouldBeTrue()
        val executor = Executors.newSingleThreadExecutor()
        val contender = LettuceLeaderElector(
            connection,
            LeaderElectionOptions(
                waitTime = 700.milliseconds,
                leaseTime = 2.seconds,
            )
        )

        try {
            val result = contender.runAsyncIfLeader(lockName, executor) {
                completableFutureOf("unexpected")
            }
            val marker = futureOf(executor) { "executor-free" }

            marker.get(300, TimeUnit.MILLISECONDS) shouldBeEqualTo "executor-free"
            result.get(2.seconds).shouldBeNull()
        } finally {
            holderLock.unlock()
            executor.shutdownNow()
        }
    }

    // =========================================================================
    // MultithreadingTester 동시성 테스트
    // =========================================================================

    @Test
    fun `MultithreadingTester - 동시 리더 선출 상호 배제 검증`() {
        val el = LettuceLeaderElector(connection, options)
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val executed = AtomicInteger(0)

        MultithreadingTester()
            .workers(5)
            .rounds(3)
            .add {
                el.runIfLeader(lockName) {
                    val current = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { max -> maxOf(max, current) }
                    Thread.sleep(10)
                    concurrent.decrementAndGet()
                    executed.incrementAndGet()
                }
            }
            .run()

        log.debug { "executed=$executed, maxConcurrent = $maxConcurrent" }
        maxConcurrent.get() shouldBeEqualTo 1
        executed.get() shouldBeGreaterOrEqualTo 1
    }

    @Test
    fun `MultithreadingTester - 동시 비동기 리더 선출 안정성`() {
        val el = LettuceLeaderElector(connection, options)
        val executed = AtomicInteger(0)

        MultithreadingTester()
            .workers(5)
            .rounds(3)
            .add {
                el.runAsyncIfLeader(lockName) {
                    futureOf { executed.incrementAndGet() }
                }.get(3.seconds)
            }
            .run()

        log.debug { "executed=$executed" }
        executed.get() shouldBeGreaterOrEqualTo 1
    }

    // =========================================================================
    // StructuredTaskScopeTester 동시성 테스트
    // =========================================================================

    @Test
    fun `StructuredTaskScopeTester - 동시 리더 선출 상호 배제 검증`() {
        val el = LettuceLeaderElector(connection, options)
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val executed = AtomicInteger(0)

        StructuredTaskScopeTester()
            .rounds(15)
            .add {
                el.runIfLeader(lockName) {
                    val current = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { max -> maxOf(max, current) }
                    Thread.sleep(10)
                    concurrent.decrementAndGet()
                    executed.incrementAndGet()
                }
            }
            .run()

        log.debug { "executed=$executed, maxConcurrent = $maxConcurrent" }
        maxConcurrent.get() shouldBeEqualTo 1
        executed.get() shouldBeGreaterOrEqualTo 1
    }

    @Test
    fun `StructuredTaskScopeTester - 동시 비동기 리더 선출 안정성`() {
        val el = LettuceLeaderElector(connection, options)
        val executed = AtomicInteger(0)

        StructuredTaskScopeTester()
            .rounds(15)
            .add {
                el.runAsyncIfLeader(lockName) {
                    futureOf { executed.incrementAndGet() }
                }.get(3.seconds)
            }
            .run()

        log.debug { "executed=$executed" }
        executed.get() shouldBeGreaterOrEqualTo 1
    }
}
