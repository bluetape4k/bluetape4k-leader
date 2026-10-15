package io.bluetape4k.leader.history

import java.time.Instant

/**
 * 명시된 status가 있으면 그대로 사용하고, 없으면 `lockedUntil`과 기준 시각으로 `ACQUIRED` 또는 `EXPIRED`를 계산합니다.
 * @param now 만료 여부를 비교할 기준 시각. 기본값은 현재 시각입니다.
 * @return record의 유효 lifecycle status
 */
fun LeaderLockHistoryRecord.effectiveStatus(now: Instant = Instant.now()): LeaderHistoryStatus {
    if (status != null) return status
    return if (lockedUntil.isBefore(now)) LeaderHistoryStatus.EXPIRED else LeaderHistoryStatus.ACQUIRED
}
