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
import kotlin.time.Duration

/**
 * `RedissonLockExtendDelegate`는 Redis Redisson 단일 lock의 lease 연장과 현재 ownership 확인을 elector에서 분리해 위임합니다.
 *
 * @property redissonClient owner-aware atomic lease extension script를 실행하는 Redisson client입니다.
 * @property lock 현재 elector가 보유한 `RLock`입니다.
 * @property acquiringThreadId Redisson이 owner를 구분할 때 사용하는 획득 thread ID입니다.
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

    /** IO dispatcher에서 owner-aware lease extension을 수행하며 coroutine 취소는 그대로 전파합니다. */
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
