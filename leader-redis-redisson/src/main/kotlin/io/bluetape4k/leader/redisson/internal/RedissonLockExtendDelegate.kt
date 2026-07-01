package io.bluetape4k.leader.redisson.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.redisson.api.RLock
import org.redisson.api.RedissonClient
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

/**
 * [ExtendDelegate] for Redisson [RLock] (sync blocking) — T8 PR 3 (Issue #79).
 *
 * ## Behavior / Contract
 * - [extend]: owner-atomic — verifies Redisson's owner hash field and renews key TTL in one Redis Lua script.
 *   Returns [ExtendOutcome.WrongThread] on thread mismatch (AC-8), [ExtendOutcome.NotHeld] when not holding the lock.
 *   Backend exceptions are wrapped as [ExtendOutcome.BackendError].
 * - [extendSuspend]: Redisson sync is blocking I/O, so dispatched with `withContext(Dispatchers.IO)` + `ensureActive()` (R9 / AC-21).
 * - [isHeld]: delegates to `lock.isHeldByThread(acquiringThreadId)`.
 * - [lastExtendDeadline]: single `AtomicReference(Instant.EPOCH)` instance — used for R2 watchdog skip.
 *
 * ## RLock TTL renewal mechanism
 * Redisson's [RLock] stores ownership in a Redis hash field named like `clientId:threadId`.
 * The extend path uses the same owner field and performs `HEXISTS` + `PEXPIRE` in one Lua script,
 * avoiding the check-then-expire race where a stale owner could renew a successor lock.
 *
 * AC-21: blocking backend [ExtendDelegate.extendSuspend] default is never called — this class overrides it explicitly.
 *
 * @property redissonClient Redisson client used to renew the lock key TTL
 * @property lock Redisson [RLock] instance
 * @property acquiringThreadId thread id used when acquiring the lock ([Thread.currentThread.threadId]).
 *           Redisson identifies lock owners by thread id, so only the same thread can extend in sync mode.
 */
internal class RedissonLockExtendDelegate(
    private val redissonClient: RedissonClient,
    private val lock: RLock,
    private val acquiringThreadId: Long,
): ExtendDelegate {

    companion object: KLogging()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        doExtend(lockAtMostFor)

    /**
     * Redisson sync API is a Netty client-based blocking facade — dispatched with `withContext(Dispatchers.IO)`.
     *
     * **Do not use `runCatching {}`** — it can swallow [CancellationException] inside a suspend function.
     * Use manual try/catch with `catch(CancellationException) { throw e }`.
     */
    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        try {
            doExtend(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Redisson extendSuspend failed. lockName=${lock.name}, threadId=$acquiringThreadId" }
            ExtendOutcome.BackendError(e)
        }
    }

    override fun isHeld(): Boolean =
        try {
            lock.isHeldByThread(acquiringThreadId)
        } catch (e: Exception) {
            log.warn(e) { "Redisson isHeld failed. lockName=${lock.name}, threadId=$acquiringThreadId" }
            false
        }

    private fun doExtend(lockAtMostFor: Duration): ExtendOutcome {
        return try {
            RedissonOwnerAtomicExtend.extend(redissonClient, lock, acquiringThreadId, lockAtMostFor)
        } catch (e: Exception) {
            log.warn(e) { "Redisson extend failed. lockName=${lock.name}, threadId=$acquiringThreadId" }
            ExtendOutcome.BackendError(e)
        }
    }
}
