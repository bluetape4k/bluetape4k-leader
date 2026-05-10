package io.bluetape4k.leader.lettuce

import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.logging.KLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeInRange
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class LettuceSuspendLeaderGroupElectorTest: AbstractLettuceLeaderTest() {

    companion object: KLogging()

    private val maxLeaders = 3
    private val options = LeaderGroupElectionOptions(maxLeaders, 5.seconds, 10.seconds)

    private lateinit var suspendElection: LettuceSuspendLeaderGroupElector
    private lateinit var lockName: String

    @BeforeEach
    fun setup() {
        suspendElection = LettuceSuspendLeaderGroupElector(connection, options)
        lockName = randomName()
    }

    @AfterEach
    fun teardown() {
        connection.sync().del("lg:{$lockName}")
    }

    @Test
    fun `코루틴 리더 선출 성공`() = runSuspendIO {
        val result = suspendElection.runIfLeader(lockName) { "suspend-done" }
        result shouldBeEqualTo "suspend-done"
    }

    @Test
    fun `코루틴 복수 리더 동시 실행`() = runSuspendIO {
        val counter = AtomicInteger(0)
        val jobs = List(maxLeaders) {
            async {
                suspendElection.runIfLeader(lockName) {
                    counter.incrementAndGet()
                }
            }
        }
        jobs.awaitAll()
        counter.get() shouldBeEqualTo maxLeaders
    }

    @Test
    fun `코루틴 상태 조회`() = runSuspendIO {
        val state = suspendElection.state(lockName)
        state.maxLeaders shouldBeEqualTo maxLeaders
        state.activeCount shouldBeEqualTo 0
    }

    // =========================================================================
    // 확장 함수
    // =========================================================================

    @Test
    fun `확장 함수로 LettuceLeaderGroupElector 생성`() {
        val el = connection.leaderGroupElection(options)
        el.shouldNotBeNull()
        val result = el.runIfLeader(lockName) { "ext" }
        result shouldBeEqualTo "ext"
    }

    @Test
    fun `확장 함수로 LettuceSuspendLeaderGroupElector 생성`() = runSuspendIO {
        val el = connection.suspendLeaderGroupElector(options)
        el.shouldNotBeNull()
        val result = el.runIfLeader(lockName) { "ext-suspend" }
        result shouldBeEqualTo "ext-suspend"
    }

    // =========================================================================
    // SuspendedJobTester 동시성 테스트
    // =========================================================================

    @Test
    fun `SuspendedJobTester - 코루틴 동시 리더 그룹 선출 maxLeaders 제한 검증`() = runSuspendIO {
        val el = LettuceSuspendLeaderGroupElector(connection, options)
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val executed = AtomicInteger(0)

        SuspendedJobTester()
            .workers(maxLeaders * 2)
            .rounds(maxLeaders * 3)
            .add {
                el.runIfLeader(lockName) {
                    val current = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { max -> maxOf(max, current) }
                    delay(20.milliseconds)
                    concurrent.decrementAndGet()
                    executed.incrementAndGet()
                }
            }
            .run()

        maxConcurrent.get() shouldBeInRange 1..maxLeaders
        executed.get() shouldBeGreaterOrEqualTo 1
    }

    @Test
    fun `SuspendedJobTester - 코루틴 리더 그룹 총 실행 횟수 검증`() = runSuspendIO {
        val el = LettuceSuspendLeaderGroupElector(connection, options)
        val executed = AtomicInteger(0)
        val rounds = 10

        SuspendedJobTester()
            .workers(maxLeaders)
            .rounds(rounds)
            .add {
                el.runIfLeader(lockName) {
                    executed.incrementAndGet()
                }
            }
            .run()

        executed.get() shouldBeEqualTo rounds
    }

    // =========================================================================
    // minLeaseTime 시맨틱 (slot-token TTL 모델)
    // =========================================================================

    @Test
    fun `minLeaseTime 보유 - 빠른 action 종료 후에도 다른 client 는 즉시 acquire 실패한다`() = runSuspendIO {
        val opts = LeaderGroupElectionOptions(
            maxLeaders = 1,
            waitTime = 100.milliseconds,
            leaseTime = 10.seconds,
            minLeaseTime = 800.milliseconds,
        )
        val el = LettuceSuspendLeaderGroupElector(connection, opts)
        el.runIfLeader(lockName) { "fast" } shouldBeEqualTo "fast"

        val secondElector = LettuceSuspendLeaderGroupElector(connection, opts)
        secondElector.runIfLeader(lockName) { "should-not" } shouldBeEqualTo null
    }

    @Test
    fun `minLeaseTime 만료 후 다음 acquire 가 성공한다 (suspend)`() = runSuspendIO {
        val opts = LeaderGroupElectionOptions(
            maxLeaders = 1,
            waitTime = 100.milliseconds,
            leaseTime = 10.seconds,
            minLeaseTime = 300.milliseconds,
        )
        val el = LettuceSuspendLeaderGroupElector(connection, opts)
        el.runIfLeader(lockName) { "first" } shouldBeEqualTo "first"

        delay(400.milliseconds)

        val secondElector = LettuceSuspendLeaderGroupElector(connection, opts)
        secondElector.runIfLeader(lockName) { "second" } shouldBeEqualTo "second"
    }

    @Test
    fun `minLeaseTime=0 회귀 - 즉시 release 가 정상 동작한다 (suspend)`() = runSuspendIO {
        val opts = LeaderGroupElectionOptions(
            maxLeaders = 1,
            waitTime = 100.milliseconds,
            leaseTime = 10.seconds,
        )
        val el = LettuceSuspendLeaderGroupElector(connection, opts)
        el.runIfLeader(lockName) { "a" } shouldBeEqualTo "a"

        val secondElector = LettuceSuspendLeaderGroupElector(connection, opts)
        secondElector.runIfLeader(lockName) { "b" } shouldBeEqualTo "b"
    }

    @Test
    fun `maxLeaders 동시 점유 + 모두 minLease 보유 - 추가 client 는 실패한다 (suspend)`() = runSuspendIO {
        val opts = LeaderGroupElectionOptions(
            maxLeaders = 2,
            waitTime = 100.milliseconds,
            leaseTime = 10.seconds,
            minLeaseTime = 1.seconds,
        )
        val el = LettuceSuspendLeaderGroupElector(connection, opts)
        repeat(opts.maxLeaders) {
            el.runIfLeader(lockName) { "fast" } shouldBeEqualTo "fast"
        }

        val third = LettuceSuspendLeaderGroupElector(connection, opts)
        third.runIfLeader(lockName) { "third" } shouldBeEqualTo null
    }

    @Test
    fun `crash recovery - release 미호출 시 leaseTime 만료 후 다른 client 가 acquire 한다 (suspend)`() = runSuspendIO {
        val crashLockName = randomName()
        val crashGroup = io.bluetape4k.leader.lettuce.semaphore.LettuceSlotTokenGroup(
            connection, crashLockName, maxLeaders = 1
        )
        crashGroup.tryAcquireSuspending(waitTime = 200.milliseconds, leaseTime = 400.milliseconds)

        val opts = LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 1.seconds, leaseTime = 5.seconds)
        val el = LettuceSuspendLeaderGroupElector(connection, opts)
        delay(500.milliseconds)
        val result = el.runIfLeader(crashLockName) { "recovered" }
        result shouldBeEqualTo "recovered"

        connection.sync().del("lg:{$crashLockName}")
    }

    @Test
    fun `코루틴 취소 + minLeaseTime 보유 - NonCancellable 로 score 갱신 보장`() = runSuspendIO {
        val opts = LeaderGroupElectionOptions(
            maxLeaders = 1,
            waitTime = 200.milliseconds,
            leaseTime = 10.seconds,
            minLeaseTime = 800.milliseconds,
        )
        val el = LettuceSuspendLeaderGroupElector(connection, opts)
        val cancelLock = randomName()

        kotlinx.coroutines.coroutineScope {
            val deferred = async {
                el.runIfLeader(cancelLock) {
                    delay(50.milliseconds)
                    "cancelled-action"
                }
            }
            // action 진입 직후 취소
            delay(20.milliseconds)
            deferred.cancelAndJoin()
        }

        // 취소 후에도 minLease 동안은 다른 client 가 진입 못 해야 함
        val secondElector = LettuceSuspendLeaderGroupElector(connection, opts)
        secondElector.runIfLeader(cancelLock) { "should-not" } shouldBeEqualTo null

        // minLease 만료 후 정상 acquire
        delay(900.milliseconds)
        secondElector.runIfLeader(cancelLock) { "later" } shouldBeEqualTo "later"

        connection.sync().del("lg:{$cancelLock}")
    }
}
