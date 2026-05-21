package io.bluetape4k.leader.exposed.r2dbc.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.exposed.r2dbc.lock.ExposedR2dbcLock
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * [SuspendExtendDelegate] for [ExposedR2dbcLock] (suspend native, reactive R2DBC driver) — T11 PR 6 (Issue #79).
 *
 * ## Behavior / Contract
 * - Exposed R2DBC is reactive native → `suspendTransaction(db)` is a suspend function
 * - [extendSuspend] : Calls `lock.extendDetailed(d)` directly (suspend native — `withContext(IO)` is not needed)
 * - [isHeldSuspend] : Calls `lock.isHeldByCurrentInstance()` directly
 *
 * Token-based lock with no thread affinity (Exposed R2DBC does not use [ExtendOutcome.WrongThread]).
 *
 * ## CancellationException handling
 * All `catch(Exception)` blocks are preceded by `catch(CancellationException) { throw e }` — coroutine cancellation contract guaranteed.
 */
internal class ExposedR2dbcSuspendLockExtendDelegate(
    private val lock: ExposedR2dbcLock,
): SuspendExtendDelegate {

    companion object: KLoggingChannel()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            lock.extendDetailed(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Exposed R2DBC suspend extendSuspend failed. lockName=${lock.lockName}" }
            ExtendOutcome.BackendError(e)
        }

    override suspend fun isHeldSuspend(): Boolean =
        try {
            lock.isHeldByCurrentInstance()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Exposed R2DBC suspend isHeld failed. lockName=${lock.lockName}" }
            false
        }
}
