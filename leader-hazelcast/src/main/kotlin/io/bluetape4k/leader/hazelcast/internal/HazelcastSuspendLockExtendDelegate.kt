package io.bluetape4k.leader.hazelcast.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.hazelcast.lock.HazelcastSuspendLock
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * [ExtendDelegate] for [HazelcastSuspendLock] — T12 PR 7 (Issue #79).
 *
 * ## Behavior / Contract
 * - Hazelcast IMap is a native blocking API — the suspend lock only adds a `withContext(Dispatchers.IO)` wrapper.
 * - [extend] (sync): bridges via `runBlocking` since only suspend functions are exposed.
 *   **Do not call from a production sync path** — only called from the watchdog scheduler thread.
 * - [extendSuspend]: calls `lock.extendDetailed(d)` directly (lock already handles `withContext(IO)`).
 * - [isHeld]: suspend function → `runBlocking` bridge.
 *
 * Token-based lock — no thread affinity.
 */
internal class HazelcastSuspendLockExtendDelegate(
    private val lock: HazelcastSuspendLock,
) : ExtendDelegate {

    companion object : KLoggingChannel()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    /**
     * Sync entry point — called from the watchdog's scheduler thread.
     *
     * `runBlocking` bridge — prefer the suspend wrapper ([extendSuspend]) when possible.
     */
    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            runBlocking { lock.extendDetailed(lockAtMostFor) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "HazelcastSuspend extend failed. lockKey=${lock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            lock.extendDetailed(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "HazelcastSuspend extendSuspend failed. lockKey=${lock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    override fun isHeld(): Boolean =
        try {
            runBlocking { lock.isHeldByCurrentInstance() }
        } catch (e: Exception) {
            log.warn(e) { "HazelcastSuspend isHeld failed. lockKey=${lock.lockKey}" }
            false
        }
}
