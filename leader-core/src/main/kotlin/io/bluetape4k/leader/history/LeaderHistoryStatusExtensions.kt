package io.bluetape4k.leader.history

import java.time.Instant

/**
 * `LeaderLockHistoryRecord`는 leader lock lifecycle event를 저장하는 audit record입니다.
 *
 * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @param now `now` 호출 또는 상태 계산에 필요한 값입니다.
 * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
 */
fun LeaderLockHistoryRecord.effectiveStatus(now: Instant = Instant.now()): LeaderHistoryStatus {
    if (status != null) return status
    return if (lockedUntil.isBefore(now)) LeaderHistoryStatus.EXPIRED else LeaderHistoryStatus.ACQUIRED
}
