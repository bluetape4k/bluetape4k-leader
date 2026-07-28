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
 * `RedissonSuspendLockExtendDelegate`는 Redis Redisson backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property redissonClient Redis Redisson backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property lock Redis Redisson backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property acquiringThreadId Redis Redisson backend 호출과 상태 계산에 사용하는 속성입니다.
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
