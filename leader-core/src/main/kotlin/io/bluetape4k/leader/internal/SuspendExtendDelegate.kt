package io.bluetape4k.leader.internal

import io.bluetape4k.leader.ExtendOutcome
import kotlin.time.Duration

/**
 * `SuspendExtendDelegate` 선언은 leader election 계약에서 사용되는 interface입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
interface SuspendExtendDelegate : ExtendDelegate {

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
