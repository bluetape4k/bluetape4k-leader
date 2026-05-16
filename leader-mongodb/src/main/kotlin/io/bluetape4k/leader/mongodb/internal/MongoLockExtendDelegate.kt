package io.bluetape4k.leader.mongodb.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.mongodb.lock.MongoLock
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
 * [ExtendDelegate] for [MongoLock] (sync, blocking driver) — T9 PR 4 (Issue #79).
 *
 * ## Behavior / Contract
 * - [extend] : Returns the result of `lock.extendDetailed(d)` as-is. Backend exceptions are wrapped in [ExtendOutcome.BackendError]
 * - [extendSuspend] : Since the MongoDB sync driver is blocking IO, wraps with `withContext(Dispatchers.IO)` + `ensureActive()` (R9 / AC-21)
 * - [isHeld] : Delegates to `lock.isHeldByCurrentInstance()`
 * - [lastExtendDeadline] : Stored in a single `AtomicReference(Instant.EPOCH)` instance — used to skip the R2 watchdog
 *
 * Token-based lock with no thread affinity (MongoDB does not use WrongThread).
 */
internal class MongoLockExtendDelegate(
    private val lock: MongoLock,
) : ExtendDelegate {

    companion object : KLogging()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            lock.extendDetailed(lockAtMostFor)
        } catch (e: Exception) {
            log.warn(e) { "MongoDB extend failed. lockKey=${lock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    /**
     * The MongoDB sync driver is blocking — dispatched with `withContext(Dispatchers.IO)`.
     *
     * **Do not use runCatching {}** — it can swallow CancellationException inside a suspend function.
     * Use manual try/catch with `catch(CancellationException) { throw e }` instead.
     */
    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        try {
            lock.extendDetailed(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "MongoDB extendSuspend failed. lockKey=${lock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }
    }

    override fun isHeld(): Boolean =
        try {
            lock.isHeldByCurrentInstance()
        } catch (e: Exception) {
            log.warn(e) { "MongoDB isHeld failed. lockKey=${lock.lockKey}" }
            false
        }
}
