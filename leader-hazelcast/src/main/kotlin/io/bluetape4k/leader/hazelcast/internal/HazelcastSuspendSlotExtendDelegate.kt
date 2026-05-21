package io.bluetape4k.leader.hazelcast.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.hazelcast.lock.HazelcastSuspendLock
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * [SuspendExtendDelegate] for the per-slot [HazelcastSuspendLock] (`{lockName}:slot:N`) of the Hazelcast suspend group elector —
 * T12 PR 7 (Issue #79).
 *
 * ## Behavior / Contract
 * - [extendSuspend]: calls `slotLock.extendDetailed(d)` directly (lock already handles `withContext(IO)`).
 * - [isHeldSuspend]: calls `slotLock.isHeldByCurrentInstance()` directly.
 */
internal class HazelcastSuspendSlotExtendDelegate(
    private val slotLock: HazelcastSuspendLock,
) : SuspendExtendDelegate {

    companion object : KLoggingChannel()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            slotLock.extendDetailed(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "HazelcastSuspend group extendSuspend failed. slotKey=${slotLock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    override suspend fun isHeldSuspend(): Boolean =
        try {
            slotLock.isHeldByCurrentInstance()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "HazelcastSuspend group isHeld failed. slotKey=${slotLock.lockKey}" }
            false
        }
}
