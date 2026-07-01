package io.bluetape4k.leader.redisson.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.logging.coroutines.KLoggingChannel
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
 * [SuspendExtendDelegate] for Redisson [RLock] (suspend variant) — T8 PR 3 (Issue #79).
 *
 * Redisson's async API is [java.util.concurrent.CompletableFuture]-based.
 *
 * ## Meaning of acquiringThreadId
 * Redisson's [RLock] identifies lock owners by a `long` identifier. The sync variant uses
 * `Thread.currentThread().threadId()`, but the suspend variant cannot rely on thread id because
 * coroutines may hop between threads. Therefore [io.bluetape4k.leader.redisson.RedissonSuspendLeaderElector]
 * issues a PID-seeded Snowflake-like ID (`lockId: Long`) and uses it as Redisson's thread-id slot.
 * This delegate stores that `lockId` as [acquiringThreadId]. Redisson's [RLock.isHeldByThread]
 * simply compares the owner field without interpreting the semantic meaning of the long value.
 *
 * ## RLock TTL renewal mechanism
 * Redisson's [RLock] stores ownership in a Redis hash field named like `clientId:threadId`.
 * The extend path uses the same owner field and performs `HEXISTS` + `PEXPIRE` in one Lua script,
 * avoiding the check-then-expire race where a stale owner could renew a successor lock.
 *
 * ## Behavior / Contract
 * - [extendSuspend]: owner-atomic — verifies owner and renews key TTL in one Redis Lua script.
 *   Returns [ExtendOutcome.WrongThread] on owner-id mismatch (AC-8).
 * - [isHeldSuspend]: delegates to `lock.isHeldByThread(acquiringThreadId)` through the suspend contract.
 *
 * @property redissonClient Redisson client used to renew the lock key TTL
 * @property lock Redisson [RLock] instance
 * @property acquiringThreadId owner id used when acquiring the lock (PID-seeded Snowflake-like `Long`)
 */
internal class RedissonSuspendLockExtendDelegate(
    private val redissonClient: RedissonClient,
    private val lock: RLock,
    private val acquiringThreadId: Long,
): SuspendExtendDelegate {

    companion object: KLoggingChannel()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        try {
            doExtendSuspend(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "RedissonSuspend extendSuspend failed. lockName=${lock.name}, ownerId=$acquiringThreadId" }
            ExtendOutcome.BackendError(e)
        }
    }

    override suspend fun isHeldSuspend(): Boolean =
        try {
            lock.isHeldByThread(acquiringThreadId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "RedissonSuspend isHeld failed. lockName=${lock.name}, ownerId=$acquiringThreadId" }
            false
        }

    private suspend fun doExtendSuspend(lockAtMostFor: Duration): ExtendOutcome {
        return RedissonOwnerAtomicExtend.extendSuspend(redissonClient, lock, acquiringThreadId, lockAtMostFor)
    }
}
