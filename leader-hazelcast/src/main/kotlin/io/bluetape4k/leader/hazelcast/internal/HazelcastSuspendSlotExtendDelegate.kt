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
 * `HazelcastSuspendSlotExtendDelegate`는 Hazelcast 그룹 slot/permit의 lease 연장과 현재 ownership 확인을 elector에서 분리해 위임합니다.
 *
 * @property slotLock suspend 그룹에서 배정된 한 슬롯의 lease 연장과 보유 여부를 위임할 Hazelcast lock입니다.
 */
internal class HazelcastSuspendSlotExtendDelegate(
    private val slotLock: HazelcastSuspendLock,
): SuspendExtendDelegate {

    companion object: KLoggingChannel()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            slotLock.extendDetailed(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "HazelcastSuspend group extendSuspend failed. slotKey=${slotLock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    override suspend fun isHeldSuspend(): Boolean =
        try {
            slotLock.isHeldByCurrentInstance()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "HazelcastSuspend group isHeld failed. slotKey=${slotLock.lockKey}" }
            false
        }
}
