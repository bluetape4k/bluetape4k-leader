package io.bluetape4k.leader.lettuce.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.leader.lettuce.lock.LettuceSuspendLock
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * `LettuceSuspendLockExtendDelegate`는 Redis Lettuce 단일 lock의 lease 연장과 현재 ownership 확인을 elector에서 분리해 위임합니다.
 *
 * @property lock 현재 suspend elector가 소유한 Redis lock의 lease 연장과 보유 여부를 위임할 객체입니다.
 */
internal class LettuceSuspendLockExtendDelegate(
    private val lock: LettuceSuspendLock,
): SuspendExtendDelegate {

    companion object: KLoggingChannel()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    @Suppress("TooGenericExceptionCaught")
    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            lock.extendDetailed(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "LettuceSuspend extendSuspend failed. lockKey=${lock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun isHeldSuspend(): Boolean =
        try {
            lock.isHeldByCurrentInstance()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "LettuceSuspend isHeld failed. lockKey=${lock.lockKey}" }
            false
        }
}
