package io.bluetape4k.leader.lettuce.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.lettuce.lock.LettuceLock
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
 * [ExtendDelegate] for [LettuceLock] (sync blocking) — T7 PR 2.
 *
 * ## Behavior / Contract
 * - [extend]: Returns the result of `lock.extendDetailed(d)` as-is. Backend exceptions are wrapped as [ExtendOutcome.BackendError].
 * - [extendSuspend]: Lettuce sync is blocking I/O, so it is wrapped with `withContext(Dispatchers.IO)` + `ensureActive()` (R9).
 * - [isHeld]: Delegates to `lock.isHeldByCurrentInstance()`.
 * - [lastExtendDeadline]: Stored in a single `AtomicReference(Instant.EPOCH)` instance — used for R2 watchdog skip.
 *
 * AC-21: The blocking backend's [ExtendDelegate.extendSuspend] default is never used — this class explicitly overrides it.
 */
internal class LettuceLockExtendDelegate(
    private val lock: LettuceLock,
) : ExtendDelegate {

    companion object : KLogging()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            lock.extendDetailed(lockAtMostFor)
        } catch (e: Exception) {
            log.warn(e) { "Lettuce extend failed. lockKey=${lock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    /**
     * The Lettuce sync API is a blocking facade over the Netty client — dispatched via `withContext(Dispatchers.IO)`.
     *
     * **Do not use runCatching {}** — it can swallow `CancellationException` inside a suspend function.
     * Use manual try/catch with `catch(CancellationException) { throw e }` instead.
     */
    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        try {
            lock.extendDetailed(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Lettuce extendSuspend failed. lockKey=${lock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }
    }

    override fun isHeld(): Boolean =
        try {
            lock.isHeldByCurrentInstance()
        } catch (e: Exception) {
            log.warn(e) { "Lettuce isHeld failed. lockKey=${lock.lockKey}" }
            false
        }
}
