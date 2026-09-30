package io.bluetape4k.leader.lettuce

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.SuspendLeaderHistorySink
import io.bluetape4k.leader.history.SuspendSafeLeaderHistoryRecorder
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.coroutines.delay
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

class LettuceSuspendLeaderElectorTest: AbstractLettuceLeaderTest() {

    companion object: KLoggingChannel()

    private val options = LeaderElectionOptions(waitTime = 2.seconds, 10.seconds)

    private lateinit var suspendElection: LettuceSuspendLeaderElector
    private lateinit var lockName: String

    @BeforeEach
    fun setup() {
        suspendElection = LettuceSuspendLeaderElector(connection, options)
        lockName = randomName()
    }

    @Test
    fun `코루틴 리더 선출 성공`() = runSuspendIO {
        val result = suspendElection.runIfLeader(lockName) { "suspend-done" }
        result shouldBeEqualTo "suspend-done"
    }

    @Test
    fun `코루틴 리더 선출 - 여러 번 순차 실행 가능`() = runSuspendIO {
        val r1 = suspendElection.runIfLeader(lockName) { "first" }
        val r2 = suspendElection.runIfLeader(lockName) { "second" }
        r1 shouldBeEqualTo "first"
        r2 shouldBeEqualTo "second"
    }

    @Test
    fun `코루틴 리더 선출 - action 예외 후 재선출 가능`() = runSuspendIO {
        assertFailsWith<LeaderElectionException> {
            suspendElection.runIfLeader(lockName) {
                throw LeaderElectionException("suspend 오류")
            }
        }
        val result = suspendElection.runIfLeader(lockName) { "recovered" }
        result shouldBeEqualTo "recovered"
    }

    @Test
    fun `runIfLeaderResultSuspend - action 실패는 ActionFailed 로 분류한다`() = runSuspendIO {
        val failure = LeaderElectionException("suspend result 오류")

        val result = suspendElection.runIfLeaderResultSuspend(LeaderSlot(lockName, "lettuce-suspend-node")) {
            throw failure
        }

        result.shouldBeInstanceOf<LeaderRunResult.ActionFailed>()
        result.cause.shouldBeInstanceOf<LeaderElectionException>()
        result.cause.message shouldBeEqualTo failure.message
    }

    @Test
    fun `runIfLeaderResultSuspend - CancellationException 은 ActionFailed 로 감싸지 않고 재전파한다`() = runSuspendIO {
        val cancellation = CancellationException("lettuce-suspend-cancelled")

        val thrown = assertFailsWith<CancellationException> {
            suspendElection.runIfLeaderResultSuspend<Any?>(LeaderSlot(lockName, "lettuce-suspend-node")) {
                throw cancellation
            }
        }

        thrown.message shouldBeEqualTo cancellation.message
    }

    @Test
    fun `runIfLeader - recordAcquired 취소 후에도 lock 이 해제되어 다음 호출이 성공한다`() = runSuspendIO {
        val cancelingRecorder = SuspendSafeLeaderHistoryRecorder(CancelOnAcquiredHistorySink)
        val election = LettuceSuspendLeaderElector(connection, options, cancelingRecorder)

        assertFailsWith<CancellationException> {
            election.runIfLeader(lockName) { "should-not-run" }
        }

        suspendElection.runIfLeader(lockName) { "reacquired" } shouldBeEqualTo "reacquired"
    }

    // =========================================================================
    // 확장 함수
    // =========================================================================

    @Test
    fun `확장 함수로 LettuceLeaderElector 생성`() {
        val el = connection.leaderElection(options)
        el.shouldNotBeNull()

        val result = el.runIfLeader(lockName) { "ext" }
        result shouldBeEqualTo "ext"
    }

    @Test
    fun `확장 함수로 LettuceSuspendLeaderElector 생성`() = runSuspendIO {
        val el = connection.suspendLeaderElector(options)
        el.shouldNotBeNull()

        val result = el.runIfLeader(lockName) { "ext-suspend" }
        result shouldBeEqualTo "ext-suspend"
    }

    // =========================================================================
    // SuspendedJobTester 동시성 테스트
    // =========================================================================

    @Test
    fun `SuspendedJobTester - 코루틴 동시 리더 선출 상호 배제 검증`() = runSuspendIO {
        val el = LettuceSuspendLeaderElector(connection, options)
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val executed = AtomicInteger(0)

        SuspendedJobTester()
            .rounds(15)
            .add {
                el.runIfLeader(lockName) {
                    val current = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { max -> maxOf(max, current) }
                    delay(timeMillis = Random.nextLong(10, 20))
                    concurrent.decrementAndGet()
                    executed.incrementAndGet()
                }
            }
            .run()

        log.debug { "maxConcurrent=${maxConcurrent.get()}, executed=${executed.get()}" }
        maxConcurrent.get() shouldBeEqualTo 1
        executed.get() shouldBeGreaterOrEqualTo 1
    }

    @Test
    fun `SuspendedJobTester - 코루틴 리더 선출 결과 정합성`() = runSuspendIO {
        val el = LettuceSuspendLeaderElector(connection, options)
        val counter = AtomicInteger(0)

        SuspendedJobTester()
            .rounds(12)
            .add {
                el.runIfLeader(lockName) {
                    counter.incrementAndGet()
                }
            }
            .run()

        log.debug { "counter=${counter.get()}" }
        counter.get() shouldBeGreaterOrEqualTo 1
    }

    private object CancelOnAcquiredHistorySink: SuspendLeaderHistorySink {
        override suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? {
            throw CancellationException("cancel after acquire")
        }

        override suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) {
        }

        override suspend fun recordFailed(
            key: LeaderHistoryKey,
            finishedAt: Instant,
            durationMs: Long,
            errorType: String?,
            errorMessage: String?,
        ) {
        }
    }
}
