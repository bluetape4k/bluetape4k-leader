package io.bluetape4k.leader.hazelcast.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.hazelcast.lock.HazelcastLock
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

/**
 * `HazelcastSlotExtendDelegate`는 Hazelcast backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property slotLock Hazelcast backend 호출과 상태 계산에 사용하는 속성입니다.
 */
internal class HazelcastSlotExtendDelegate(
    private val slotLock: HazelcastLock,
) : ExtendDelegate {

    companion object : KLogging()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            slotLock.extendDetailed(lockAtMostFor)
        } catch (e: Exception) {
            log.warn(e) { "Hazelcast group extend failed. slotKey=${slotLock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        try {
            slotLock.extendDetailed(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Hazelcast group extendSuspend failed. slotKey=${slotLock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }
    }

    override fun isHeld(): Boolean =
        try {
            slotLock.isHeldByCurrentInstance()
        } catch (e: Exception) {
            log.warn(e) { "Hazelcast group isHeld failed. slotKey=${slotLock.lockKey}" }
            false
        }
}
