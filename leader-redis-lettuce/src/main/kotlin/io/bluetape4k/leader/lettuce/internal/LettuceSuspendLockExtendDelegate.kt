package io.bluetape4k.leader.lettuce.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.lettuce.lock.LettuceSuspendLock
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * [ExtendDelegate] for [LettuceSuspendLock] (suspend native) — T7 PR 2.
 *
 * ## Behavior / Contract
 * - Lettuce async API is Netty event-loop-based non-blocking → suspend native.
 * - [extend] (sync): Bridges via `runBlocking` because only suspend functions are exposed. **Must not be called on a production sync path** —
 *   use [extendSuspend] after entering a suspend context. The AOP aspect calls this only from a suspend wrapper.
 * - [extendSuspend]: Calls `lock.extendDetailed(d)` directly (suspend native — no `withContext(IO)` needed).
 * - [isHeld]: Suspend function → bridged via `runBlocking` (rare path).
 */
internal class LettuceSuspendLockExtendDelegate(
    private val lock: LettuceSuspendLock,
) : ExtendDelegate {

    companion object : KLoggingChannel()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    /**
     * Sync entry point — called from the watchdog's scheduler thread.
     *
     * Because the Lettuce async API is Netty-based, `runBlocking` is safe without backpressure.
     * However, the suspend wrapper ([extendSuspend]) is preferred.
     */
    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            runBlocking { lock.extendDetailed(lockAtMostFor) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "LettuceSuspend extend failed. lockKey=${lock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            lock.extendDetailed(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "LettuceSuspend extendSuspend failed. lockKey=${lock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    override fun isHeld(): Boolean =
        try {
            runBlocking { lock.isHeldByCurrentInstance() }
        } catch (e: Exception) {
            log.warn(e) { "LettuceSuspend isHeld failed. lockKey=${lock.lockKey}" }
            false
        }
}
