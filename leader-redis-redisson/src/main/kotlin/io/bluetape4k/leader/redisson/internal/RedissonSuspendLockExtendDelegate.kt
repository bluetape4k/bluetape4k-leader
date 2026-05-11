package io.bluetape4k.leader.redisson.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.redisson.api.RLock
import org.redisson.api.RedissonClient
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

/**
 * Redisson [RLock] (suspend variant) 용 [ExtendDelegate] — T8 PR 3 (Issue #79).
 *
 * Redisson 의 async API 는 [java.util.concurrent.CompletableFuture] 기반이므로
 * `await()` 으로 suspend bridge.
 *
 * ## acquiringThreadId 의 의미
 * Redisson 의 [RLock] 은 락 소유자를 `long` 식별자로 인식한다. sync variant 는 `Thread.currentThread().threadId()`
 * 를 사용하지만, suspend variant 는 코루틴 스레드 호핑으로 인해 thread-id 가 안정적이지 않다.
 * 따라서 [io.bluetape4k.leader.redisson.RedissonSuspendLeaderElector] 는 PID-seeded Snowflake-like ID
 * (`lockId: Long`) 를 발급하여 Redisson 의 thread-id slot 으로 사용한다. 본 delegate 는 그 `lockId`
 * 를 [acquiringThreadId] 로 보관한다. Redisson 의 [RLock.isHeldByThread] 는 long 값의 의미를 검사하지
 * 않고 단순히 owner field 와 비교한다.
 *
 * ## RLock TTL 갱신 메커니즘
 * Redisson 의 [RLock] 은 [org.redisson.api.RExpirable] 을 직접 구현하지 않으므로
 * `lock.expire(d)` / `lock.expireAsync(d)` 를 호출할 수 없다.
 * 대신 [RedissonClient.getKeys] 의 `expireAsync(name, ms, MILLISECONDS)` 으로 lock 의
 * Redis key TTL 을 직접 갱신한다.
 *
 * ## 동작/계약
 * - [extendSuspend] : owner-guarded — `lock.isHeldByThread(acquiringThreadId)` 검사 후 `expireAsync(...).await()`.
 *   thread (owner-id) 불일치 시 [ExtendOutcome.WrongThread] (AC-8).
 * - [extend] (sync) : suspend 함수만 노출되므로 `runBlocking` 으로 bridge — production sync path 에서 호출 금지.
 *   watchdog scheduler thread 에서 호출됨.
 * - [isHeld] : `lock.isHeldByThread(acquiringThreadId)` 위임 (sync facade).
 *
 * @property redissonClient lock key TTL 갱신을 위한 Redisson 클라이언트
 * @property lock Redisson [RLock] instance
 * @property acquiringThreadId 락 획득 시 사용한 owner id (PID-seeded Snowflake-like `Long`).
 */
internal class RedissonSuspendLockExtendDelegate(
    private val redissonClient: RedissonClient,
    private val lock: RLock,
    private val acquiringThreadId: Long,
): ExtendDelegate {

    companion object: KLoggingChannel()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    /**
     * sync entry point — watchdog 의 scheduler thread 에서 호출됨.
     *
     * Redisson async 는 Netty 기반이므로 `runBlocking` 은 backpressure 없이 안전.
     * 그러나 suspend wrapper ([extendSuspend]) 가 우선 사용 권장.
     */
    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            runBlocking { doExtendSuspend(lockAtMostFor) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "RedissonSuspend extend failed. lockName=${lock.name}, ownerId=$acquiringThreadId" }
            ExtendOutcome.BackendError(e)
        }

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

    override fun isHeld(): Boolean =
        try {
            lock.isHeldByThread(acquiringThreadId)
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
