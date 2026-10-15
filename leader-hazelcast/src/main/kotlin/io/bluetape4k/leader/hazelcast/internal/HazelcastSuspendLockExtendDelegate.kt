package io.bluetape4k.leader.hazelcast.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.hazelcast.lock.HazelcastSuspendLock
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * `HazelcastSuspendLockExtendDelegate`는 Hazelcast 단일 lock의 lease 연장과 현재 ownership 확인을 elector에서 분리해 위임합니다.
 *
 * @property lock 현재 suspend elector가 소유한 Hazelcast lock의 lease 연장과 보유 여부를 위임할 객체입니다.
 */
internal class HazelcastSuspendLockExtendDelegate(
    private val lock: HazelcastSuspendLock,
): SuspendExtendDelegate {

    companion object: KLoggingChannel()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            lock.extendDetailed(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "HazelcastSuspend extendSuspend failed. lockKey=${lock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    override suspend fun isHeldSuspend(): Boolean =
        try {
            lock.isHeldByCurrentInstance()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "HazelcastSuspend isHeld failed. lockKey=${lock.lockKey}" }
            false
        }
}
