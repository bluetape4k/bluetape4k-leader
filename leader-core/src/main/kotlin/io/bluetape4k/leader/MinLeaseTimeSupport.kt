package io.bluetape4k.leader

import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * 단조 시계 기준 경과 시간만큼 `minLeaseTime`에서 차감해 아직 기다려야 할 시간을 계산합니다.
 * @param startedAtNanos 작업 시작 시 `System.nanoTime()`으로 기록한 값
 * @param minLeaseTime lock을 최소 보유해야 하는 기간
 * @return 남은 기간. 이미 경과했거나 `minLeaseTime`이 0 이하이면 0
 */
fun remainingMinLeaseTime(startedAtNanos: Long, minLeaseTime: Duration): Duration {
    if (minLeaseTime <= Duration.ZERO) {
        return Duration.ZERO
    }
    val elapsed = (System.nanoTime() - startedAtNanos).nanoseconds
    val remaining = minLeaseTime - elapsed
    return if (remaining > Duration.ZERO) remaining else Duration.ZERO
}

/**
 * 최소 보유 시간이 남아 있으면 현재 thread를 남은 시간만큼 park합니다.
 * @param startedAtNanos 작업 시작 시 `System.nanoTime()`으로 기록한 값
 * @param minLeaseTime lock을 최소 보유해야 하는 기간
 */
internal fun parkRemainingMinLeaseTime(startedAtNanos: Long, minLeaseTime: Duration) {
    val remaining = remainingMinLeaseTime(startedAtNanos, minLeaseTime)
    if (remaining > Duration.ZERO) {
        LockSupport.parkNanos(remaining.inWholeNanoseconds)
    }
}
