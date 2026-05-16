package io.bluetape4k.leader.hazelcast.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.hazelcast.lock.HazelcastLock
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
 * [ExtendDelegate] for [HazelcastLock] (sync, blocking IMap) — T12 PR 7 (Issue #79).
 *
 * ## Behavior / Contract
 * - [extend]: delegates to `lock.extendDetailed(d)`. Backend exceptions are wrapped as [ExtendOutcome.BackendError].
 * - [extendSuspend]: Hazelcast IMap is blocking IO — uses `withContext(Dispatchers.IO)` + `ensureActive()` (R9 / AC-21).
 * - [isHeld]: delegates to `lock.isHeldByCurrentInstance()`.
 * - [lastExtendDeadline]: held in a single `AtomicReference(Instant.EPOCH)` instance (for R2 watchdog skip).
 *
 * Token-based lock — no thread affinity (Hazelcast does not use WrongThread).
 */
internal class HazelcastLockExtendDelegate(
    private val lock: HazelcastLock,
) : ExtendDelegate {

    companion object : KLogging()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            lock.extendDetailed(lockAtMostFor)
        } catch (e: Exception) {
            log.warn(e) { "Hazelcast extend failed. lockKey=${lock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    /**
     * Hazelcast IMap is blocking — dispatched via `withContext(Dispatchers.IO)`.
     *
     * **Do not use runCatching {}** — it may swallow CancellationException inside suspend functions.
     * Use manual try/catch with `catch(CancellationException) { throw e }`.
     */
    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        try {
            lock.extendDetailed(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Hazelcast extendSuspend failed. lockKey=${lock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }
    }

    override fun isHeld(): Boolean =
        try {
            lock.isHeldByCurrentInstance()
        } catch (e: Exception) {
            log.warn(e) { "Hazelcast isHeld failed. lockKey=${lock.lockKey}" }
            false
        }
}
