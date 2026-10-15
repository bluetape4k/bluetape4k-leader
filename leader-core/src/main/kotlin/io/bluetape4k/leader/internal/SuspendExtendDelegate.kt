package io.bluetape4k.leader.internal

import io.bluetape4k.leader.ExtendOutcome
import kotlin.time.Duration

/**
 * suspend 전용 backend를 위한 delegate입니다. 잘못된 blocking 호출은 `BackendError`로 보고하며 lock을 보유했다고 답하지 않습니다.
 */
interface SuspendExtendDelegate: ExtendDelegate {

    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        ExtendOutcome.BackendError(
            UnsupportedOperationException(
                "SuspendExtendDelegate requires extendSuspend(); sync extend() is unsupported.",
            ),
        )

    override fun isHeld(): Boolean = false

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome

    suspend fun isHeldSuspend(): Boolean
}
