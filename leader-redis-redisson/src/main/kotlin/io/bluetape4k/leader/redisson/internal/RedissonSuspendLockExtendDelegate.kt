package io.bluetape4k.leader.redisson.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
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
 * Redisson's async API is [java.util.concurrent.CompletableFuture]-based,
 * so it is bridged to suspend via `await()`.
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
 * Redisson's [RLock] does not directly implement [org.redisson.api.RExpirable], so
 * `lock.expire(d)` / `lock.expireAsync(d)` cannot be called. Instead, the Redis key TTL is
 * renewed directly via [RedissonClient.getKeys] `expireAsync(name, ms, MILLISECONDS)`.
 *
 * ## Behavior / Contract
 * - [extendSuspend]: owner-guarded — checks `lock.isHeldByThread(acquiringThreadId)` then calls `expireAsync(...).await()`.
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
        // AC-8: owner-id mismatch / 미보유 → WrongThread / NotHeld
        if (!lock.isHeldByThread(acquiringThreadId)) {
            return if (lock.remainTimeToLive() >= 0L) {
                ExtendOutcome.WrongThread
            } else {
                ExtendOutcome.NotHeld
            }
        }
        val javaDuration = java.time.Duration.ofNanos(lockAtMostFor.inWholeNanoseconds)
        // RKeysAsync.expireAsync(Duration, vararg String) → 갱신된 key 개수 반환 (>= 1 이면 성공).
        val updated = redissonClient.keys.expireAsync(javaDuration, lock.name)
            .toCompletableFuture()
            .await()
        return if (updated >= 1L) {
            ExtendOutcome.Extended(Instant.now().plus(javaDuration))
        } else {
            ExtendOutcome.NotHeld
        }
    }
}
