package io.bluetape4k.leader.exposed.jdbc.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcLock
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
 * [ExtendDelegate] for [ExposedJdbcLock] (sync, blocking JDBC) — T10 PR 5 (Issue #79).
 *
 * ## Behavior / Contract
 * - [extend] : Returns the result of `lock.extendDetailed(d)` as-is. Backend exceptions are wrapped in
 *   [ExtendOutcome.BackendError] — the classifier then categorizes them as TRANSIENT/NON_TRANSIENT.
 * - [extendSuspend] : Since JDBC is blocking IO, wraps the call with `withContext(Dispatchers.IO)` + `ensureActive()`
 *   (R9 / AC-21).
 * - [isHeld] : Delegates to `lock.isHeldByCurrentInstance()` (returns false on exception).
 * - [lastExtendDeadline] : Stored in a single `AtomicReference(Instant.EPOCH)` instance — used to skip the R2 watchdog.
 *
 * Token-based lock with no thread affinity (Exposed JDBC does not use [ExtendOutcome.WrongThread]).
 */
internal class ExposedJdbcLockExtendDelegate(
    private val lock: ExposedJdbcLock,
) : ExtendDelegate {

    companion object : KLogging()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            lock.extendDetailed(lockAtMostFor)
        } catch (e: Exception) {
            log.warn(e) { "Exposed JDBC extend failed. lockName=${lock.lockName}" }
            ExtendOutcome.BackendError(e)
        }

    /**
     * JDBC is blocking — dispatched with `withContext(Dispatchers.IO)`.
     *
     * **Do not use runCatching {}** — it can swallow [CancellationException] inside a suspend function.
     * Use manual try/catch with `catch(CancellationException) { throw e }` instead.
     */
    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        try {
            lock.extendDetailed(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Exposed JDBC extendSuspend failed. lockName=${lock.lockName}" }
            ExtendOutcome.BackendError(e)
        }
    }

    override fun isHeld(): Boolean =
        try {
            lock.isHeldByCurrentInstance()
        } catch (e: Exception) {
            log.warn(e) { "Exposed JDBC isHeld failed. lockName=${lock.lockName}" }
            false
        }
}
