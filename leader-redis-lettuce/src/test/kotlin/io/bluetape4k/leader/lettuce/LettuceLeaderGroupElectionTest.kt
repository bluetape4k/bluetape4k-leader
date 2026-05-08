package io.bluetape4k.leader.lettuce

import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.leader.LeaderGroupElectionException
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterOrEqualTo
import org.amshove.kluent.shouldBeInRange
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import io.bluetape4k.assertions.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class LettuceLeaderGroupElectionTest: AbstractLettuceLeaderTest() {

    companion object: KLogging()

    private val maxLeaders = 3
    private val options = LeaderGroupElectionOptions(maxLeaders, 5.seconds, 10.seconds)

    private lateinit var election: LettuceLeaderGroupElector
    private lateinit var lockName: String

    @BeforeEach
    fun setup() {
        election = LettuceLeaderGroupElector(connection, options)
        lockName = randomName()
    }

    @AfterEach
    fun teardown() {
        connection.sync().del(lockName)
    }

    // =========================================================================
    // 상태 조회
    // =========================================================================

    @Test
    fun `maxLeaders 값 확인`() {
        election.maxLeaders shouldBeEqualTo maxLeaders
    }

    @Test
    fun `초기 상태 - availableSlots = maxLeaders`() {
        val slots = election.availableSlots(lockName)
        slots shouldBeEqualTo maxLeaders
    }

    @Test
    fun `초기 상태 - activeCount = 0`() {
        val active = election.activeCount(lockName)
        active shouldBeEqualTo 0
    }

    @Test
    fun `state 조회`() {
        val state = election.state(lockName)
        state.lockName shouldBeEqualTo lockName
        state.maxLeaders shouldBeEqualTo maxLeaders
        state.activeCount shouldBeEqualTo 0
        state.isEmpty.shouldBeTrue()
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
        val r1 = election.runIfLeader(lockName) { 1 }
        val r2 = election.runIfLeader(lockName) { 2 }
        val r3 = election.runIfLeader(lockName) { 3 }
        r1 shouldBeEqualTo 1
        r2 shouldBeEqualTo 2
        r3 shouldBeEqualTo 3
    }

    @Test
    fun `action 예외 발생 시 슬롯 반환 후 재선출 가능`() {
        assertFailsWith<LeaderGroupElectionException> {
            election.runIfLeader(lockName) { throw LeaderGroupElectionException("오류") }
        }
        val result = election.runIfLeader(lockName) { "recovered" }
        result shouldBeEqualTo "recovered"
    }

    // =========================================================================
    // 비동기 API
    // =========================================================================

    @Test
    fun `비동기 리더 선출 성공`() {
        val result = election.runAsyncIfLeader(lockName) {
            CompletableFuture.completedFuture("async-done")
        }.get()
        result shouldBeEqualTo "async-done"
    }

    @Test
    fun `비동기 복수 리더 동시 실행`() {
        val counter = AtomicInteger(0)
        val futures = List(maxLeaders) { i ->
            election.runAsyncIfLeader(lockName) {
                CompletableFuture.supplyAsync {
                    Thread.sleep(50)
                    counter.incrementAndGet()
                    "result-$i"
                }
            }
        }
        CompletableFuture.allOf(*futures.toTypedArray()).get()
        counter.get() shouldBeEqualTo maxLeaders
    }

    // =========================================================================
    // MultithreadingTester 동시성 테스트
    // =========================================================================

    @Test
    fun `MultithreadingTester - 동시 리더 그룹 선출 maxLeaders 제한 검증`() {
        val el = LettuceLeaderGroupElector(connection, options)
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val executed = AtomicInteger(0)

        MultithreadingTester()
            .workers(maxLeaders * 2)
            .rounds(3)
            .add {
                el.runIfLeader(lockName) {
                    val current = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { max -> maxOf(max, current) }
                    Thread.sleep(20)
                    concurrent.decrementAndGet()
                    executed.incrementAndGet()
                }
            }
            .run()

        maxConcurrent.get() shouldBeInRange 1..maxLeaders
        executed.get() shouldBeGreaterOrEqualTo 1
    }

    @Test
    fun `MultithreadingTester - 동시 비동기 리더 그룹 선출 안정성`() {
        val el = LettuceLeaderGroupElector(connection, options)
        val executed = AtomicInteger(0)

        MultithreadingTester()
            .workers(maxLeaders * 2)
            .rounds(3)
            .add {
                el.runAsyncIfLeader(lockName) {
                    CompletableFuture.supplyAsync {
                        Thread.sleep(10)
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
    fun `StructuredTaskScopeTester - 동시 리더 그룹 선출 maxLeaders 제한 검증`() {
        val el = LettuceLeaderGroupElector(connection, options)
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val executed = AtomicInteger(0)

        StructuredTaskScopeTester()
            .rounds(maxLeaders * 4)
            .add {
                el.runIfLeader(lockName) {
                    val current = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { max -> maxOf(max, current) }
                    Thread.sleep(20)
                    concurrent.decrementAndGet()
                    executed.incrementAndGet()
                }
            }
            .run()

        maxConcurrent.get() shouldBeInRange 1..maxLeaders
        executed.get() shouldBeGreaterOrEqualTo 1
    }
}
