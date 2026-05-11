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
 * Exposed R2DBC suspend group elector 의 per-slot [ExposedR2dbcGroupLock] (`(lockName, slot)`) 용 [ExtendDelegate]
 * — T11 PR 6 (Issue #79).
 *
 * Exposed R2DBC 는 reactive native → suspend.
 *
 * ## 동작/계약
 * - [extend] (sync) : `runBlocking` bridge — watchdog scheduler thread 호출 안전 (R2DBC reactive)
 * - [extendSuspend] : `slotLock.extendDetailed(d)` 직접 호출 (suspend native)
 * - [isHeld] : `runBlocking` bridge
 *
 * Token-based 락이므로 thread 종속성 없음.
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
