package io.bluetape4k.leader.exposed.jdbc.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcGroupLock
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

/**
 * [ExtendDelegate] for per-slot [ExposedJdbcGroupLock] (`(lockName, slot)`) in the Exposed JDBC group elector
 * — T10 PR 5 (Issue #79).
 *
 * ## Behavior / Contract
 * - [extend] : Delegates to `slotLock.extendDetailed(d)`. Applies the R6 guard (`lockedUntil > now`).
 * - [extendSuspend] : JDBC is blocking — wraps with `withContext(Dispatchers.IO)` + `ensureActive()` (R9 / AC-21).
 * - [isHeld] : Delegates to `slotLock.isHeldByCurrentInstance()`.
 */
internal class ExposedJdbcSlotExtendDelegate(
    private val slotLock: ExposedJdbcGroupLock,
) : ExtendDelegate {

    companion object : KLogging()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            slotLock.extendDetailed(lockAtMostFor)
        } catch (e: Exception) {
            log.warn(e) { "Exposed JDBC group extend failed. lockName=${slotLock.lockName}, slot=${slotLock.slot}" }
            ExtendOutcome.BackendError(e)
        }

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        try {
            slotLock.extendDetailed(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Exposed JDBC group extendSuspend failed. lockName=${slotLock.lockName}, slot=${slotLock.slot}" }
            ExtendOutcome.BackendError(e)
        }
    }

    override fun isHeld(): Boolean =
        try {
            slotLock.isHeldByCurrentInstance()
        } catch (e: Exception) {
            log.warn(e) { "Exposed JDBC group isHeld failed. lockName=${slotLock.lockName}, slot=${slotLock.slot}" }
            false
        }
}
