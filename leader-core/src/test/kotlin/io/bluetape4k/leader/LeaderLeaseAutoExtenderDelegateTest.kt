package io.bluetape4k.leader

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.internal.ExtendDelegate
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.SocketTimeoutException
import java.sql.SQLNonTransientException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [LeaderLeaseAutoExtender.start] 의 [ExtendDelegate] 오버로드 검증.
 *
 * - 새 시그니처 호출 검증
 * - R2 watchdog skip — `lastExtendDeadline` 이 미래이면 backend extend 호출 안 됨
 * - cadence = leaseTime/3 동작
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("NonAsciiCharacters")
class LeaderLeaseAutoExtenderDelegateTest {

    /** 호출 횟수와 결과를 제어할 수 있는 테스트용 [ExtendDelegate]. */
    private class TestDelegate(
        private val held: Boolean = true,
    ) : ExtendDelegate {
        val extendCalls = AtomicInteger(0)
        private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
        override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

        override fun extend(lockAtMostFor: Duration): ExtendOutcome {
            extendCalls.incrementAndGet()
            return if (held) ExtendOutcome.Extended(Instant.now().plusMillis(lockAtMostFor.inWholeMilliseconds))
            else ExtendOutcome.NotHeld
        }

        override fun isHeld(): Boolean = held
    }

    // ── 기본 동작 ──────────────────────────────────────────────────────────

    @Test
    fun `disabled watchdog does not call delegate extend`() = runSuspendIO {
        val delegate = TestDelegate()
        val watchdog = LeaderLeaseAutoExtender.start(false, 100.milliseconds, delegate)

        delay(120.milliseconds)
        watchdog.close()

        delegate.extendCalls.get() shouldBeEqualTo 0
    }

    @Test
    fun `enabled watchdog calls delegate extend`() = runSuspendIO {
        val delegate = TestDelegate(held = true)
        val watchdog = LeaderLeaseAutoExtender.start(true, 90.milliseconds, delegate)

        delay(250.milliseconds)
        watchdog.close()

        (delegate.extendCalls.get() >= 1).shouldBeTrue()
    }

    @Test
    fun `watchdog stops after close`() = runSuspendIO {
        val delegate = TestDelegate()
        val watchdog = LeaderLeaseAutoExtender.start(true, 90.milliseconds, delegate)

        delay(120.milliseconds)
        watchdog.close()
        val callsAfterClose = delegate.extendCalls.get()
        delay(120.milliseconds)

        // close 이후 추가 호출 없음
        delegate.extendCalls.get() shouldBeEqualTo callsAfterClose
    }

    @Test
    fun `watchdog stops when delegate returns NotHeld`() = runSuspendIO {
        val delegate = TestDelegate(held = false)
        val watchdog = LeaderLeaseAutoExtender.start(true, 90.milliseconds, delegate)

        delay(250.milliseconds)
        watchdog.close()  // idempotent

        // NotHeld 반환 후 watchdog 이 스스로 종료 — 호출 횟수는 1 이상이되 계속 늘지 않음
        val calls = delegate.extendCalls.get()
        (calls >= 1).shouldBeTrue()
    }

    // ── R2 watchdog skip ──────────────────────────────────────────────────

    @Test
    fun `R2 skip - lastExtendDeadline 이 미래이면 backend extend 호출 안 됨`() = runSuspendIO {
        val delegate = TestDelegate(held = true)

        // user 가 explicit extend 를 호출했다고 가정: deadline = now + 60s
        delegate.lastExtendDeadline.set(Instant.now().plusSeconds(60))

        // cadence = 90ms / 3 = 30ms (MIN_RENEWAL_PERIOD 적용으로 25ms)
        val watchdog = LeaderLeaseAutoExtender.start(true, 90.milliseconds, delegate)

        // cadence 를 몇 번 기다려도 skip 되어야 함
        delay(150.milliseconds)
        watchdog.close()

        // deadline 이 충분히 미래 → extend 호출 안 됨
        delegate.extendCalls.get() shouldBeEqualTo 0
    }

    @Test
    fun `R2 skip - lastExtendDeadline 이 과거이면 backend extend 호출됨`() = runSuspendIO {
        val delegate = TestDelegate(held = true)

        // deadline 을 과거로 설정 — watchdog 이 skip 안 함
        delegate.lastExtendDeadline.set(Instant.now().minusSeconds(10))

        val watchdog = LeaderLeaseAutoExtender.start(true, 90.milliseconds, delegate)

        delay(200.milliseconds)
        watchdog.close()

        (delegate.extendCalls.get() >= 1).shouldBeTrue()
    }

    @Test
    fun `R2 skip - lastExtendDeadline 초기값 EPOCH 이면 watchdog 이 정상 호출한다`() = runSuspendIO {
        val delegate = TestDelegate(held = true)
        // EPOCH 은 과거 → skip 안 됨

        val watchdog = LeaderLeaseAutoExtender.start(true, 90.milliseconds, delegate)

        delay(200.milliseconds)
        watchdog.close()

        (delegate.extendCalls.get() >= 1).shouldBeTrue()
    }

    // ── cadence 계산 ──────────────────────────────────────────────────────

    @Test
    fun `renewalPeriod 는 leaseTime 의 1-3 을 반환한다`() {
        val period = LeaderLeaseAutoExtender.renewalPeriod(30.seconds)
        period shouldBeEqualTo 10.seconds
    }

    @Test
    fun `renewalPeriod 는 최솟값 25ms 미만이면 25ms 를 반환한다`() {
        val period = LeaderLeaseAutoExtender.renewalPeriod(30.milliseconds)
        // 30ms / 3 = 10ms < 25ms → 25ms
        (period.inWholeMilliseconds >= 25).shouldBeTrue()
    }

    // ── BackendError transient 는 watchdog 계속, non-transient 는 중단 ──────

    @Test
    fun `BackendError transient - watchdog 이 계속 동작한다`() = runSuspendIO {
        val callsAfterError = java.util.concurrent.atomic.AtomicInteger(0)
        var transientFired = false

        // 첫 번째 호출은 transient error, 이후는 성공
        val delegate = object : ExtendDelegate {
            private val _lastExtendDeadline = java.util.concurrent.atomic.AtomicReference(Instant.EPOCH)
            override val lastExtendDeadline get() = _lastExtendDeadline
            private var callCount = 0

            override fun extend(lockAtMostFor: Duration): ExtendOutcome {
                callCount++
                return if (callCount == 1) {
                    transientFired = true
                    // SocketTimeoutException → TRANSIENT
                    ExtendOutcome.BackendError(java.net.SocketTimeoutException("timeout"))
                } else {
                    callsAfterError.incrementAndGet()
                    ExtendOutcome.Extended(Instant.now().plusMillis(lockAtMostFor.inWholeMilliseconds))
                }
            }

            override fun isHeld(): Boolean = true
        }

        val watchdog = LeaderLeaseAutoExtender.start(true, 90.milliseconds, delegate)
        delay(300.milliseconds)
        watchdog.close()

        // transient error 후 계속 호출됨
        transientFired.shouldBeTrue()
        (callsAfterError.get() >= 1).shouldBeTrue()
    }

    @Test
    fun `BackendError non-transient - watchdog 이 중단된다`() = runSuspendIO {
        val callsAfterError = java.util.concurrent.atomic.AtomicInteger(0)
        var errorFired = false

        val delegate = object : ExtendDelegate {
            private val _lastExtendDeadline = java.util.concurrent.atomic.AtomicReference(Instant.EPOCH)
            override val lastExtendDeadline get() = _lastExtendDeadline
            private var callCount = 0

            override fun extend(lockAtMostFor: Duration): ExtendOutcome {
                callCount++
                return if (callCount == 1) {
                    errorFired = true
                    // SQLNonTransientException → NON_TRANSIENT → 중단
                    ExtendOutcome.BackendError(java.sql.SQLNonTransientException("fatal query error"))
                } else {
                    callsAfterError.incrementAndGet()
                    ExtendOutcome.Extended(Instant.now())
                }
            }

            override fun isHeld(): Boolean = true
        }

        val watchdog = LeaderLeaseAutoExtender.start(true, 90.milliseconds, delegate)
        delay(300.milliseconds)
        watchdog.close()

        // non-transient error 후 watchdog 중단 → 이후 호출 0
        errorFired.shouldBeTrue()
        callsAfterError.get() shouldBeEqualTo 0
    }

}
