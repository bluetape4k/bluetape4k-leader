package io.bluetape4k.leader.hazelcast.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.hazelcast.lock.HazelcastSuspendLock
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * [SuspendExtendDelegate] for [HazelcastSuspendLock] — T12 PR 7 (Issue #79).
 *
 * ## Behavior / Contract
 * - Hazelcast IMap is a native blocking API — the suspend lock only adds a `withContext(Dispatchers.IO)` wrapper.
 * - [extendSuspend]: calls `lock.extendDetailed(d)` directly (lock already handles `withContext(IO)`).
 * - [isHeldSuspend]: calls `lock.isHeldByCurrentInstance()` directly.
 *
 * Token-based lock — no thread affinity.
 */
internal class HazelcastSuspendLockExtendDelegate(
    private val lock: HazelcastSuspendLock,
) : SuspendExtendDelegate {

    companion object : KLoggingChannel()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            lock.extendDetailed(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "HazelcastSuspend extendSuspend failed. lockKey=${lock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    override suspend fun isHeldSuspend(): Boolean =
        try {
            lock.isHeldByCurrentInstance()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "HazelcastSuspend isHeld failed. lockKey=${lock.lockKey}" }
            false
        }
}
