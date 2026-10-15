package io.bluetape4k.leader.mongodb.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.mongodb.lock.MongoLock
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * `MongoLockExtendDelegate`는 MongoDB 단일 lock의 lease 연장과 현재 ownership 확인을 elector에서 분리해 위임합니다.
 *
 * @property lock 현재 elector가 소유한 MongoDB lock의 lease 연장과 보유 여부를 위임할 객체입니다.
 */
internal class MongoLockExtendDelegate(
    private val lock: MongoLock,
): ExtendDelegate {

    companion object: KLogging()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            lock.extendDetailed(lockAtMostFor)
        } catch (e: Exception) {
            log.warn(e) { "MongoDB extend failed. lockKey=${lock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    /** IO dispatcher에서 MongoDB lease 연장을 수행하며 coroutine 취소는 그대로 전파합니다. */
    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        try {
            lock.extendDetailed(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "MongoDB extendSuspend failed. lockKey=${lock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }
    }

    override fun isHeld(): Boolean =
        try {
            lock.isHeldByCurrentInstance()
        } catch (e: Exception) {
            log.warn(e) { "MongoDB isHeld failed. lockKey=${lock.lockKey}" }
            false
        }
}
