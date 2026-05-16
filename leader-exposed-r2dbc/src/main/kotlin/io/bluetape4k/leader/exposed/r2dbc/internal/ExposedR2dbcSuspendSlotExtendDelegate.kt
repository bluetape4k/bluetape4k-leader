package io.bluetape4k.leader.exposed.r2dbc.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.exposed.r2dbc.lock.ExposedR2dbcGroupLock
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * [ExtendDelegate] for per-slot [ExposedR2dbcGroupLock] (`(lockName, slot)`) in the Exposed R2DBC suspend group elector
 * — T11 PR 6 (Issue #79).
 *
 * Exposed R2DBC is reactive native → suspend.
 *
 * ## Behavior / Contract
 * - [extend] (sync) : `runBlocking` bridge — safe to call from the watchdog scheduler thread (R2DBC reactive)
 * - [extendSuspend] : Calls `slotLock.extendDetailed(d)` directly (suspend native)
 * - [isHeld] : `runBlocking` bridge
 *
 * Token-based lock with no thread affinity.
 */
internal class ExposedR2dbcSuspendSlotExtendDelegate(
    private val slotLock: ExposedR2dbcGroupLock,
): ExtendDelegate {

    companion object: KLoggingChannel()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            runBlocking { slotLock.extendDetailed(lockAtMostFor) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Exposed R2DBC suspend group extend failed. lockName=${slotLock.lockName}, slot=${slotLock.slot}" }
            ExtendOutcome.BackendError(e)
        }

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            slotLock.extendDetailed(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Exposed R2DBC suspend group extendSuspend failed. lockName=${slotLock.lockName}, slot=${slotLock.slot}" }
            ExtendOutcome.BackendError(e)
        }

    override fun isHeld(): Boolean =
        try {
            runBlocking { slotLock.isHeldByCurrentInstance() }
        } catch (e: Exception) {
            log.warn(e) { "Exposed R2DBC suspend group isHeld failed. lockName=${slotLock.lockName}, slot=${slotLock.slot}" }
            false
        }
}
