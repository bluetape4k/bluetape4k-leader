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
 * `RedissonLockExtendDelegate`는 Redis Redisson backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property redissonClient Redis Redisson backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property lock Redis Redisson backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property acquiringThreadId Redis Redisson backend 호출과 상태 계산에 사용하는 속성입니다.
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
     * `extendSuspend` 호출은 Redis Redisson backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
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
