package io.bluetape4k.leader.coroutines

import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class LocalSuspendLeaderElectionTest {

    companion object: KLoggingChannel()

    private val election = LocalSuspendLeaderElection()

    private fun randomLockName() = "lock-${UUID.randomUUID()}"

    @Test
    fun `runIfLeader - 리더로 선출되어 suspend action 을 실행하고 결과를 반환한다`() = runSuspendIO {
        val result = election.runIfLeader(randomLockName()) { "hello" }
        result shouldBeEqualTo "hello"
    }

    @Test
    fun `runIfLeader - 서로 다른 lockName 은 독립적으로 실행된다`() = runSuspendIO {
        val result1 = election.runIfLeader(randomLockName()) { "a" }
        val result2 = election.runIfLeader(randomLockName()) { "b" }

        result1 shouldBeEqualTo "a"
        result2 shouldBeEqualTo "b"
    }

    @Test
    fun `runIfLeader - action 예외 발생 시 예외가 호출자에게 전파된다`() = runSuspendIO {
        val result = runCatching {
            election.runIfLeader(randomLockName()) {
                throw RuntimeException("테스트 예외")
            }
        }
        result.isFailure.shouldBeTrue()
        (result.exceptionOrNull() is RuntimeException).shouldBeTrue()
    }

    @Test
    fun `runIfLeader - action 예외 후에도 Mutex 가 해제되어 다음 호출이 성공한다`() = runSuspendIO {
        val lockName = randomLockName()
        runCatching {
            election.runIfLeader(lockName) { throw RuntimeException("실패") }
        }

        // Mutex 가 해제된 상태여야 다음 호출이 정상 실행된다
        val result = election.runIfLeader(lockName) { "복구 성공" }
        result shouldBeEqualTo "복구 성공"
    }

    @Test
    fun `runIfLeader - delay 를 포함한 suspend action 이 정상 실행된다`() = runSuspendIO {
        val result = election.runIfLeader(randomLockName()) {
            delay(10.milliseconds)
            "delay 완료"
        }
        result shouldBeEqualTo "delay 완료"
    }

    @Test
    fun `runIfLeader - 여러 코루틴 동시 실행 시 직렬 처리를 보장한다`() = runSuspendIO {
        val lockName = randomLockName()
        val task1 = AtomicInteger(0)
        val task2 = AtomicInteger(0)
        val numWorkers = 8
        val roundsPerJob = 4

        // SuspendedJobTester: rounds * numBlocks 개의 Job 을 생성
        // block 당 rounds 번 실행 → task1 = task2 = numWorkers * roundsPerJob = 32
        SuspendedJobTester()
            .workers(numWorkers)
            .rounds(numWorkers * roundsPerJob)
            .add {
                election.runIfLeader(lockName) {
                    log.debug { "suspend 작업 1 실행. task1=${task1.get()}" }
                    delay(Random.nextLong(1, 5).milliseconds)
                    task1.incrementAndGet()
                }
            }
            .add {
                election.runIfLeader(lockName) {
                    log.debug { "suspend 작업 2 실행. task2=${task2.get()}" }
                    delay(Random.nextLong(1, 5).milliseconds)
                    task2.incrementAndGet()
                }
            }
            .run()

        task1.get() shouldBeEqualTo numWorkers * roundsPerJob
        task2.get() shouldBeEqualTo numWorkers * roundsPerJob
    }

    // ── skip-behavior (ShedLock 방식): 락 획득 실패 시 null 반환 ──────────

    @Test
    fun `runIfLeader - waitTime 내 Mutex 획득 실패 시 null 을 반환한다`() = runSuspendIO {
        val shortWaitElection = LocalSuspendLeaderElection(
            LeaderElectionOptions(waitTime = Duration.ofMillis(100))
        )
        val longWaitElection = LocalSuspendLeaderElection(
            LeaderElectionOptions(waitTime = Duration.ofSeconds(30))
        )
        val lockName = randomLockName()
        val mutex = Mutex()
        mutex.lock() // 외부에서 Mutex 를 잠금

        // 첫 번째 코루틴: 오래 걸리는 작업 수행
        val firstJob = async {
            longWaitElection.runIfLeader(lockName) {
                mutex.lock() // 외부 뮤텍스가 잠겨있는 동안 대기 (시뮬레이션)
                mutex.unlock()
                "done"
            }
        }

        // 실제로는 LocalSuspendLeaderElection 내부 Mutex 를 사용하므로
        // 두 개의 전자를 순차 실행하고 waitTime 으로 테스트
        // 간단한 방법: 락 보유 중인 코루틴이 있을 때 짧은 waitTime 으로 시도
        mutex.unlock()
        firstJob.await()

        // 명확한 skip-behavior 테스트: 내부 Mutex 를 직접 잠근 상태에서 시도
        // LocalSuspendLeaderElection 의 내부 mutexes map 에 접근할 수 없으므로
        // 대신 두 코루틴을 사용하여 race condition 을 만듦
        val skipElection = LocalSuspendLeaderElection(
            LeaderElectionOptions(waitTime = Duration.ofMillis(50))
        )
        val holderReady = kotlinx.coroutines.channels.Channel<Unit>(1)
        val holderDone = kotlinx.coroutines.channels.Channel<Unit>(1)
        val lockName2 = randomLockName()

        // 홀더: 락 획득 후 300ms 유지
        val holder = async {
            skipElection.runIfLeader(lockName2) {
                holderReady.send(Unit)
                delay(300.milliseconds)
                "holder"
            }
        }

        holderReady.receive() // 홀더가 락 획득할 때까지 대기
        // 스키퍼: 짧은 waitTime(50ms) 으로 시도 → null 반환
        val skipped = skipElection.runIfLeader(lockName2) { "should-skip" }
        skipped.shouldBeNull()

        holder.await()
    }

    @Test
    fun `runIfLeader - 락 해제 후 재시도 시 정상 실행된다`() = runSuspendIO {
        val election = LocalSuspendLeaderElection(
            LeaderElectionOptions(waitTime = Duration.ofMillis(100))
        )
        val lockName = randomLockName()

        val first = election.runIfLeader(lockName) { "first" }
        first shouldBeEqualTo "first"

        val second = election.runIfLeader(lockName) { "second" }
        second shouldBeEqualTo "second"
    }
}
