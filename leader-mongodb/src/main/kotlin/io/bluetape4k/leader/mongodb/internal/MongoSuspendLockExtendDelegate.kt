package io.bluetape4k.leader.mongodb.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.leader.mongodb.lock.MongoSuspendLock
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * [SuspendExtendDelegate] for [MongoSuspendLock] (suspend native, reactive coroutine driver) — T9 PR 4 (Issue #79).
 *
 * ## Behavior / Contract
 * - The MongoDB coroutine driver (`com.mongodb.kotlin.client.coroutine.MongoCollection`) is reactive native → suspend
 * - [extendSuspend] : Calls `lock.extendDetailed(d)` directly (suspend native — withContext(IO) is not needed)
 * - [isHeldSuspend] : Calls `lock.isHeldByCurrentInstance()` directly
 *
 * Token-based lock with no thread affinity (MongoDB does not use WrongThread).
 */
internal class MongoSuspendLockExtendDelegate(
    private val lock: MongoSuspendLock,
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
            log.warn(e) { "MongoSuspend extendSuspend failed. lockKey=${lock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    override suspend fun isHeldSuspend(): Boolean =
        try {
            lock.isHeldByCurrentInstance()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "MongoSuspend isHeld failed. lockKey=${lock.lockKey}" }
            false
        }
}
