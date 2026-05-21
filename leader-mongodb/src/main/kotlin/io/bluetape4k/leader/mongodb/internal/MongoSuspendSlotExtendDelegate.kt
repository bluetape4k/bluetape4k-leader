package io.bluetape4k.leader.mongodb.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.leader.mongodb.lock.MongoSuspendLock
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * [SuspendExtendDelegate] for per-slot [MongoSuspendLock] (`{lockName}:slot:N`) in the MongoDB suspend group elector —
 * T9 PR 4 (Issue #79).
 *
 * The MongoDB coroutine driver is reactive native → suspend.
 *
 * ## Behavior / Contract
 * - [extendSuspend] : Calls `slotLock.extendDetailed(d)` directly (suspend native)
 * - [isHeldSuspend] : Calls `slotLock.isHeldByCurrentInstance()` directly
 */
internal class MongoSuspendSlotExtendDelegate(
    private val slotLock: MongoSuspendLock,
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
            log.warn(e) { "MongoSuspend group extendSuspend failed. slotKey=${slotLock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    override suspend fun isHeldSuspend(): Boolean =
        try {
            slotLock.isHeldByCurrentInstance()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "MongoSuspend group isHeld failed. slotKey=${slotLock.lockKey}" }
            false
        }
}
