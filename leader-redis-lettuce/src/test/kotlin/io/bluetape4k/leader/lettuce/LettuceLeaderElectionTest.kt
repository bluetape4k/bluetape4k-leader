package io.bluetape4k.leader.lettuce

import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterOrEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class LettuceLeaderElectionTest: AbstractLettuceLeaderTest() {

    companion object: KLogging()

    private val options = LeaderElectionOptions(waitTime = Duration.ofSeconds(2), Duration.ofSeconds(10))

    private lateinit var election: LettuceLeaderElection
    private lateinit var lockName: String

    @BeforeEach
    fun setup() {
        election = LettuceLeaderElection(connection, options)
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
    fun `리더 선출 - action 예외 발생 시 예외 전파`() {
        assertThrows<LeaderElectionException> {
            election.runIfLeader(lockName) { throw LeaderElectionException("오류") }
        }
    }

    @Test
    fun `리더 선출 - action 예외 후 락 해제되어 재선출 가능`() {
        assertThrows<LeaderElectionException> {
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
        val el = LettuceLeaderElection(connection, options)
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
        val el = LettuceLeaderElection(connection, options)
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
        val el = LettuceLeaderElection(connection, options)
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
