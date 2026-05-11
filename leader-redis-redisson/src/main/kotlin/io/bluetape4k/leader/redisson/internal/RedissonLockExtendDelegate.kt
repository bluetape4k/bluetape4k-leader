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
 * Redisson [RLock] (sync blocking) 용 [ExtendDelegate] — T8 PR 3 (Issue #79).
 *
 * ## 동작/계약
 * - [extend] : owner-guarded 단계 — `lock.isHeldByThread(acquiringThreadId)` 검사 후 `RKeys.expire(d, key)` 실행.
 *   thread 불일치 시 [ExtendOutcome.WrongThread] (AC-8). lock 미보유 시 [ExtendOutcome.NotHeld].
 *   backend exception 은 [ExtendOutcome.BackendError] 로 wrap.
 * - [extendSuspend] : Redisson sync 는 blocking I/O 이므로 `withContext(Dispatchers.IO)` + `ensureActive()` (R9 / AC-21).
 * - [isHeld] : `lock.isHeldByThread(acquiringThreadId)` 위임.
 * - [lastExtendDeadline] : `AtomicReference(Instant.EPOCH)` 단일 인스턴스 — R2 watchdog skip 용.
 *
 * ## RLock TTL 갱신 메커니즘
 * Redisson 의 [RLock] 은 [org.redisson.api.RExpirable] 을 직접 구현하지 않으므로
 * `lock.expire(d)` 를 호출할 수 없다. 대신 [RedissonClient.getKeys] 의
 * `expire(duration, keyName)` 으로 lock 의 Redis key TTL 을 직접 갱신한다.
 * 이는 Redisson 내장 watchdog 이 사용하는 방식과 동일.
 *
 * ## Owner-guard race window
 * `isHeldByThread` 검사와 `expire(d)` 호출 사이 race window 가 존재한다. 검사 직후 takeover
 * 가 일어나면 새 owner 의 lease 가 갱신되는 좁은 race. 본 PR 에서는 명시적으로 acceptable race 로
 * 문서화한다 — 완전 atomic 보장이 필요하면 follow-up PR 에서 hand-rolled Lua (`HEXISTS` + `PEXPIRE`)
 * 로 대체.
 *
 * AC-21: blocking backend [ExtendDelegate.extendSuspend] 가 default 사용 0회 — 본 클래스가 명시적으로 override.
 *
 * @property redissonClient lock key TTL 갱신을 위한 Redisson 클라이언트
 * @property lock Redisson [RLock] instance
 * @property acquiringThreadId 락 획득 시 사용한 thread id ([Thread.currentThread.threadId]).
 *           Redisson 은 lock 소유자를 thread id 단위로 식별하므로, sync elector 는 동일 thread 만 extend 가능.
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
     * Redisson sync API 는 Netty client 기반 blocking facade — `withContext(Dispatchers.IO)` 로 dispatch.
     *
     * **runCatching {} 사용 금지** — suspend 안에서 CancellationException 을 삼킬 수 있음.
     * 수동 try/catch + `catch(CancellationException) { throw e }` 사용.
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
