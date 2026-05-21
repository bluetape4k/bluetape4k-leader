package io.bluetape4k.leader.internal

import io.bluetape4k.leader.ExtendOutcome
import kotlin.time.Duration

/**
 * Coroutine-native extension SPI for suspend backend modules.
 *
 * ## Behavior / Contract
 * - [extendSuspend] performs the backend atomic extend without `runBlocking`.
 * - [isHeldSuspend] checks backend ownership without `runBlocking`.
 * - Sync [extend] and [isHeld] are misuse paths for suspend-only delegates. They do not bridge to
 *   suspend APIs because the suspend watchdog and `LockExtender.extendActiveLockDetailedSuspend` must call
 *   the suspend entry points directly.
 *
 * Application code must not implement this directly. Backend modules use it to share one delegate
 * reference between `LeaderLockHandle.Real` and `LeaderLeaseAutoExtender`.
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
