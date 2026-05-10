package io.bluetape4k.leader.lettuce

import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.logging.KLogging
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import io.bluetape4k.assertions.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

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

        Thread.sleep(450)

        el.runIfLeader(lockName) { "after-min" } shouldBeEqualTo "after-min"
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
                    release.await(1, java.util.concurrent.TimeUnit.SECONDS)
                    "holder"
                }
            }

            started.await(1, java.util.concurrent.TimeUnit.SECONDS)
            Thread.sleep(450)

            el.runIfLeader(lockName) { "contender" }.shouldBeNull()

            release.countDown()
            holder.get(2, java.util.concurrent.TimeUnit.SECONDS) shouldBeEqualTo "holder"
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
            CompletableFuture.completedFuture("async-done")
        }
        future.get() shouldBeEqualTo "async-done"
    }

    @Test
    fun `비동기 리더 선출 - 여러 번 순차 실행 가능`() {
        val r1 = election.runAsyncIfLeader(lockName) {
            CompletableFuture.completedFuture(1)
        }.get()
        val r2 = election.runAsyncIfLeader(lockName) {
            CompletableFuture.completedFuture(2)
        }.get()
        r1 shouldBeEqualTo 1
        r2 shouldBeEqualTo 2
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

        maxConcurrent.get() shouldBeEqualTo 1
        executed.get() shouldBeGreaterOrEqualTo 1
    }

    @Test
    fun `MultithreadingTester - 동시 비동기 리더 선출 안정성`() {
        val el = LettuceLeaderElector(connection, options)
        val executed = AtomicInteger(0)

        MultithreadingTester()
            .workers(4)
            .rounds(3)
            .add {
                el.runAsyncIfLeader(lockName) {
                    CompletableFuture.supplyAsync {
                        executed.incrementAndGet()
                    }
                }.get()
            }
            .run()

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
            .rounds(10)
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

        maxConcurrent.get() shouldBeEqualTo 1
        executed.get() shouldBeGreaterOrEqualTo 1
    }
}
