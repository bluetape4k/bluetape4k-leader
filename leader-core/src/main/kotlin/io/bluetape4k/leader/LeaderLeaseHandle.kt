package io.bluetape4k.leader

import java.time.Instant
import kotlin.time.Duration

/**
 * 획득된 요청별 lease의 ownership과 cleanup 경계입니다.
 *
 * handle은 backend token이나 delegate를 공개하지 않습니다. `release`와 `close`는
 * 반복 호출 및 completion thread 변경에 안전해야 합니다.
 */
interface LeaderLeaseHandle : AutoCloseable {

    /** backend lock 이름입니다. */
    val lockName: String

    /** audit에 사용할 leader identity입니다. */
    val auditLeaderId: String

    /** lease가 공개된 시각입니다. */
    val acquiredAt: Instant

    /** 지정된 기간만큼 fencing-aware lease extension을 시도합니다. */
    fun extend(lockAtMostFor: Duration): ExtendOutcome

    /** 현재 generation의 ownership 상태를 확인합니다. */
    fun ownershipStatus(): LeaseOwnershipStatus

    /** ownership이 명확하게 확인된 경우에만 true입니다. */
    fun isStillHeld(): Boolean

    /** idempotent conditional release를 수행합니다. */
    fun release()

    /** `release`와 동일한 terminal 경계입니다. */
    override fun close() = release()
}
