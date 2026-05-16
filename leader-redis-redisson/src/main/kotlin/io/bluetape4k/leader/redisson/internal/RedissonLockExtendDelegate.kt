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
 * - [extend]: owner-guarded — checks `lock.isHeldByThread(acquiringThreadId)` then calls `RKeys.expire(d, key)`.
 *   Returns [ExtendOutcome.WrongThread] on thread mismatch (AC-8), [ExtendOutcome.NotHeld] when not holding the lock.
 *   Backend exceptions are wrapped as [ExtendOutcome.BackendError].
 * - [extendSuspend]: Redisson sync is blocking I/O, so dispatched with `withContext(Dispatchers.IO)` + `ensureActive()` (R9 / AC-21).
 * - [isHeld]: delegates to `lock.isHeldByThread(acquiringThreadId)`.
 * - [lastExtendDeadline]: single `AtomicReference(Instant.EPOCH)` instance — used for R2 watchdog skip.
 *
 * ## RLock TTL renewal mechanism
 * Redisson's [RLock] does not directly implement [org.redisson.api.RExpirable], so `lock.expire(d)`
 * cannot be called. Instead, the Redis key TTL is renewed directly via [RedissonClient.getKeys]
 * `expire(duration, keyName)` — the same mechanism used by Redisson's built-in watchdog.
 *
 * ## Owner-guard race window
 * A race window exists between the `isHeldByThread` check and the `expire(d)` call. If a takeover
 * occurs immediately after the check, the new owner's lease may be renewed in a narrow race.
 * This is explicitly documented as an acceptable race in this PR — for full atomicity, replace with
 * a hand-rolled Lua script (`HEXISTS` + `PEXPIRE`) in a follow-up PR.
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
            // AC-8: thread-id mismatch / 미보유 → WrongThread / NotHeld
            if (!lock.isHeldByThread(acquiringThreadId)) {
                return if (lock.remainTimeToLive() >= 0L) {
                    // 다른 owner 가 보유 — Redisson 의 lock 소유자 식별 단위 (thread id) 가 다르므로 WrongThread.
                    ExtendOutcome.WrongThread
                } else {
                    ExtendOutcome.NotHeld
                }
            }
            val javaDuration = java.time.Duration.ofNanos(lockAtMostFor.inWholeNanoseconds)
            // RKeys.expire(Duration, vararg String) → 갱신된 key 개수 반환 (>= 1 이면 성공).
            val updated = redissonClient.keys.expire(javaDuration, lock.name)
            if (updated >= 1L) {
                ExtendOutcome.Extended(Instant.now().plus(javaDuration))
            } else {
                ExtendOutcome.NotHeld
            }
        } catch (e: Exception) {
            log.warn(e) { "Redisson extend failed. lockName=${lock.name}, threadId=$acquiringThreadId" }
            ExtendOutcome.BackendError(e)
        }
    }
}
