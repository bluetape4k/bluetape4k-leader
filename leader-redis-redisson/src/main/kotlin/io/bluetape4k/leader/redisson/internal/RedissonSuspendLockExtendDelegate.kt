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
import kotlin.time.Duration

/**
 * `RedissonSuspendLockExtendDelegate`는 Redis Redisson 단일 lock의 lease 연장과 현재 ownership 확인을 elector에서 분리해 위임합니다.
 *
 * @property redissonClient owner-aware atomic lease extension script를 실행하는 Redisson client입니다.
 * @property lock 현재 suspend elector가 보유한 `RLock`입니다.
 * @property acquiringThreadId suspend 경로에서 owner를 구분할 synthetic thread ID입니다.
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
