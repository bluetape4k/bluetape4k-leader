package io.bluetape4k.leader.mongodb.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.mongodb.lock.MongoSuspendLock
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * [ExtendDelegate] for [MongoSuspendLock] (suspend native, reactive coroutine driver) — T9 PR 4 (Issue #79).
 *
 * ## Behavior / Contract
 * - The MongoDB coroutine driver (`com.mongodb.kotlin.client.coroutine.MongoCollection`) is reactive native → suspend
 * - [extend] (sync) : Since only suspend functions are exposed, uses a `runBlocking` bridge.
 *   **Do not call from a production sync path** — use [extendSuspend] after entering a suspend context.
 *   The AOP aspect calls this only from the suspend wrapper.
 * - [extendSuspend] : Calls `lock.extendDetailed(d)` directly (suspend native — withContext(IO) is not needed)
 * - [isHeld] : Suspend function → `runBlocking` bridge (rare path)
 *
 * Token-based lock with no thread affinity (MongoDB does not use WrongThread).
 */
internal class MongoSuspendLockExtendDelegate(
    private val lock: MongoSuspendLock,
) : ExtendDelegate {

    companion object : KLoggingChannel()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    /**
     * Sync entry point — called from the watchdog's scheduler thread.
     *
     * Since the MongoDB coroutine driver is reactive-based, `runBlocking` is safe without backpressure.
     * However, the suspend wrapper ([extendSuspend]) should be preferred.
     */
    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            runBlocking { lock.extendDetailed(lockAtMostFor) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "MongoSuspend extend failed. lockKey=${lock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            lock.extendDetailed(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "MongoSuspend extendSuspend failed. lockKey=${lock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    override fun isHeld(): Boolean =
        try {
            runBlocking { lock.isHeldByCurrentInstance() }
        } catch (e: Exception) {
            log.warn(e) { "MongoSuspend isHeld failed. lockKey=${lock.lockKey}" }
            false
        }
}
