package io.bluetape4k.leader

import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.local.LocalAsyncLeaderGroupElector
import io.bluetape4k.leader.local.LocalLeaderGroupElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [LeaderGroupElectionState] 인터페이스 계약을 검증하는 테스트입니다.
 *
 * [LocalLeaderGroupElector]과 [LocalAsyncLeaderGroupElector] 구현체를 통해
 * [LeaderGroupElectionState]의 상태 조회 메서드([activeCount], [availableSlots], [state])를 검증합니다.
 */
class LeaderGroupElectionStateTest {

    companion object: KLogging()

    private val maxLeaders = 3
    private val options = LeaderGroupElectionOptions(maxLeaders)

    private fun randomLockName() = "lock-${Base58.randomString(8)}"

    // ── LocalLeaderGroupElector 을 통한 LeaderGroupElectionState 계약 검증 ──

    @Test
    fun `activeCount - 초기 상태에서 0을 반환한다 (sync 구현체)`() {
        val election: LeaderGroupElectionState = LocalLeaderGroupElector(options)
        val lockName = randomLockName()
        election.activeCount(lockName) shouldBeEqualTo 0
    }

    @Test
    fun `availableSlots - 초기 상태에서 maxLeaders 를 반환한다 (sync 구현체)`() {
        val election: LeaderGroupElectionState = LocalLeaderGroupElector(options)
        val lockName = randomLockName()
        election.availableSlots(lockName) shouldBeEqualTo maxLeaders
    }

    @Test
    fun `state - 초기 상태 스냅샷이 정확하다 (sync 구현체)`() {
        val election: LeaderGroupElectionState = LocalLeaderGroupElector(options)
        val lockName = randomLockName()
        val state = election.state(lockName)

        state.lockName shouldBeEqualTo lockName
        state.maxLeaders shouldBeEqualTo maxLeaders
        state.activeCount shouldBeEqualTo 0
        state.availableSlots shouldBeEqualTo maxLeaders
        state.isEmpty.shouldBeTrue()
        state.isFull.shouldBeFalse()
    }

    @Test
    fun `state - 슬롯 점유 중 activeCount 가 증가한다 (sync 구현체)`() = runSuspendIO {
        val election = LocalLeaderGroupElector(options)
        val lockName = randomLockName()
        val acquiredCount = AtomicInteger(0)
        // CountDownLatch is intentional here: the action lambda is blocking (not suspend),
        // so a latch is the correct signal to hold the lock inside the action body.
        val holdLatch = CountDownLatch(1)

        coroutineScope {
            val jobs = List(2) {
                async(Dispatchers.IO) {
                    election.runIfLeader(lockName) {
                        acquiredCount.incrementAndGet()
                        holdLatch.await()
                    }
                }
            }

            withTimeout(5.seconds) {
                while (acquiredCount.get() < 2) {
                    delay(5.milliseconds)
                }
            }

            election.activeCount(lockName) shouldBeEqualTo 2
            election.availableSlots(lockName) shouldBeEqualTo maxLeaders - 2
            election.state(lockName).isEmpty.shouldBeFalse()

            holdLatch.countDown()
            jobs.awaitAll()
        }
    }

    // ── LocalAsyncLeaderGroupElector 을 통한 LeaderGroupElectionState 계약 검증 ──

    @Test
    fun `activeCount - 초기 상태에서 0을 반환한다 (async 구현체)`() {
        val election: LeaderGroupElectionState = LocalAsyncLeaderGroupElector(options)
        val lockName = randomLockName()
        election.activeCount(lockName) shouldBeEqualTo 0
    }

    @Test
    fun `availableSlots - 초기 상태에서 maxLeaders 를 반환한다 (async 구현체)`() {
        val election: LeaderGroupElectionState = LocalAsyncLeaderGroupElector(options)
        val lockName = randomLockName()
        election.availableSlots(lockName) shouldBeEqualTo maxLeaders
    }

    @Test
    fun `state - maxLeaders 프로퍼티가 올바르다`() {
        val election: LeaderGroupElectionState = LocalLeaderGroupElector(options)
        election.maxLeaders shouldBeEqualTo maxLeaders
    }
}
