package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaseOwnershipStatus
import java.time.Instant
import kotlin.time.Duration

/** suspend request lifecycle을 위한 lease handle입니다. */
interface SuspendLeaderLeaseHandle {
    /** backend lock 이름입니다. */
    val lockName: String

    /** audit에 사용할 leader identity입니다. */
    val auditLeaderId: String

    /** lease가 공개된 시각입니다. */
    val acquiredAt: Instant

    /** 지정된 기간만큼 suspend extension을 시도합니다. */
    suspend fun extend(lockAtMostFor: Duration): ExtendOutcome

    /** 현재 generation의 ownership 상태를 확인합니다. */
    suspend fun ownershipStatus(): LeaseOwnershipStatus

    /** ownership이 명확하게 확인된 경우에만 true입니다. */
    suspend fun isStillHeld(): Boolean

    /** bounded non-cancellable cleanup을 수행합니다. */
    suspend fun release()
}
