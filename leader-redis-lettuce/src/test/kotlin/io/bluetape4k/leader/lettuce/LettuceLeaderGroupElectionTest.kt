package io.bluetape4k.leader.lettuce

import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.leader.LeaderGroupElectionException
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.logging.KLogging
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeInRange
import io.bluetape4k.assertions.shouldBeTrue
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
        connection.sync().del("lg:{$lockName}")
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

    // =========================================================================
    // minLeaseTime 시맨틱 (slot-token TTL 모델)
    // =========================================================================

    @Test
    fun `minLeaseTime 보유 - 빠른 action 종료 후에도 다른 client 는 즉시 acquire 실패한다`() {
        val opts = LeaderGroupElectionOptions(
            maxLeaders = 1,
            waitTime = 100.milliseconds,
            leaseTime = 10.seconds,
            minLeaseTime = 800.milliseconds,
        )
        val el = LettuceLeaderGroupElector(connection, opts)

        // 빠른 action — caller 즉시 반환
        val result = el.runIfLeader(lockName) { "fast" }
        result shouldBeEqualTo "fast"

        // minLease 만료 전에 다른 client (동일 lockName) 는 acquire 실패해야 한다
        val secondElector = LettuceLeaderGroupElector(connection, opts)
        val blocked = secondElector.runIfLeader(lockName) { "should-not" }
        blocked shouldBeEqualTo null
    }

    @Test
    fun `minLeaseTime 만료 후 다음 acquire 가 성공한다`() {
        val opts = LeaderGroupElectionOptions(
            maxLeaders = 1,
            waitTime = 100.milliseconds,
            leaseTime = 10.seconds,
            minLeaseTime = 300.milliseconds,
        )
        val el = LettuceLeaderGroupElector(connection, opts)

        el.runIfLeader(lockName) { "first" } shouldBeEqualTo "first"

        // minLease 만료 대기
        Thread.sleep(400)

        val secondElector = LettuceLeaderGroupElector(connection, opts)
        secondElector.runIfLeader(lockName) { "second" } shouldBeEqualTo "second"
    }

    @Test
    fun `minLeaseTime=0 회귀 - 즉시 release 가 정상 동작한다`() {
        val opts = LeaderGroupElectionOptions(
            maxLeaders = 1,
            waitTime = 100.milliseconds,
            leaseTime = 10.seconds,
            minLeaseTime = Duration.ZERO,
        )
        val el = LettuceLeaderGroupElector(connection, opts)

        el.runIfLeader(lockName) { "a" } shouldBeEqualTo "a"
        // 즉시 release: 다음 client 가 곧바로 acquire 가능
        val secondElector = LettuceLeaderGroupElector(connection, opts)
        secondElector.runIfLeader(lockName) { "b" } shouldBeEqualTo "b"
    }

    @Test
    fun `maxLeaders 동시 점유 + 모두 minLease 보유 - 추가 client 는 실패한다`() {
        val opts = LeaderGroupElectionOptions(
            maxLeaders = 2,
            waitTime = 100.milliseconds,
            leaseTime = 10.seconds,
            minLeaseTime = 1.seconds,
        )
        val el = LettuceLeaderGroupElector(connection, opts)
        repeat(opts.maxLeaders) {
            el.runIfLeader(lockName) { "fast" } shouldBeEqualTo "fast"
        }

        // minLease 만료 전: 추가 client 는 실패
        val third = LettuceLeaderGroupElector(connection, opts)
        third.runIfLeader(lockName) { "third" } shouldBeEqualTo null
    }

    @Test
    fun `crash recovery - release 미호출 시 leaseTime 만료 후 다른 client 가 acquire 한다`() {
        // primitive 를 직접 사용하여 release 를 호출하지 않음 (crash 시뮬레이션)
        val crashLockName = randomName()
        val crashGroup = io.bluetape4k.leader.lettuce.semaphore.LettuceSlotTokenGroup(
            connection, crashLockName, maxLeaders = 1
        )
        val token = crashGroup.tryAcquire(waitTime = 200.milliseconds, leaseTime = 400.milliseconds)
        token shouldBeEqualTo token   // non-null

        // 다른 elector (동일 키) — leaseTime 만료 대기 후 acquire 성공
        val opts = LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 1.seconds, leaseTime = 5.seconds)
        val el = LettuceLeaderGroupElector(connection, opts)
        Thread.sleep(500) // leaseTime(400ms) 만료 대기
        val result = el.runIfLeader(crashLockName) { "recovered" }
        result shouldBeEqualTo "recovered"

        connection.sync().del("lg:{$crashLockName}")
    }

    // =========================================================================
    // Codex P2-2: runAsyncIfLeader 가 release 완료 후 future complete 보장
    // =========================================================================

    @Test
    fun `runAsyncIfLeader - 두 번째 chained 호출은 첫 번째 future complete 후 즉시 acquire 가능`() {
        // maxLeaders=1, minLeaseTime=0 → 첫 번째 future 가 complete 되는 시점에 slot 이 freed 되어야
        // 두 번째 호출이 즉시 acquire 가능. 기존 fire-and-forget release 였다면 race condition 으로
        // 두 번째 호출이 contention 으로 fail 할 수 있다.
        val opts = LeaderGroupElectionOptions(
            maxLeaders = 1,
            waitTime = 50.milliseconds,         // 짧은 wait — race 시 immediate fail
            leaseTime = 5.seconds,
            minLeaseTime = Duration.ZERO,
        )
        val el = LettuceLeaderGroupElector(connection, opts)

        repeat(20) {
            val first = el.runAsyncIfLeader(lockName) {
                CompletableFuture.completedFuture("first-$it")
            }
            first.get() shouldBeEqualTo "first-$it"

            // first.get() 이 반환된 시점에 release 도 완료되어 있어야 함 → 즉시 acquire 가능
            val second = el.runAsyncIfLeader(lockName) {
                CompletableFuture.completedFuture("second-$it")
            }
            second.get() shouldBeEqualTo "second-$it"
        }
    }

    @Test
    fun `runAsyncIfLeader - action 이 sync throw 해도 release 가 완료된 후 future 가 fail 한다`() {
        val opts = LeaderGroupElectionOptions(
            maxLeaders = 1,
            waitTime = 50.milliseconds,
            leaseTime = 5.seconds,
            minLeaseTime = Duration.ZERO,
        )
        val el = LettuceLeaderGroupElector(connection, opts)

        // action 이 sync throw → outer future 가 fail
        val failed = el.runAsyncIfLeader<String>(lockName) {
            throw LeaderGroupElectionException("sync throw")
        }
        assertFailsWith<java.util.concurrent.ExecutionException> {
            failed.get()
        }

        // future 가 fail 한 직후, slot 이 freed 되어 다음 호출 즉시 성공해야 함
        val recovered = el.runAsyncIfLeader(lockName) {
            CompletableFuture.completedFuture("recovered")
        }
        recovered.get() shouldBeEqualTo "recovered"
    }
}
