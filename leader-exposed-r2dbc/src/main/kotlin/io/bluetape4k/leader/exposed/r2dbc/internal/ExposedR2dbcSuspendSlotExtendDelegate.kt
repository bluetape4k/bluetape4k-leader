package io.bluetape4k.leader.exposed.r2dbc.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.exposed.r2dbc.lock.ExposedR2dbcGroupLock
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * `ExposedR2dbcSuspendSlotExtendDelegate`는 R2DBC group slot lock의 suspend lease 연장과 owner 확인을 watchdog에 제공합니다.
 *
 * @property slotLock 그룹 슬롯의 suspend lease 연장·소유권 조회를 위임받는 실제 R2DBC 락 객체입니다.
 */
internal class ExposedR2dbcSuspendSlotExtendDelegate(
    private val slotLock: ExposedR2dbcGroupLock,
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
            log.warn(e) { "Exposed R2DBC suspend group extendSuspend failed. lockName=${slotLock.lockName}, slot=${slotLock.slot}" }
            ExtendOutcome.BackendError(e)
        }

    override suspend fun isHeldSuspend(): Boolean =
        try {
            slotLock.isHeldByCurrentInstance()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Exposed R2DBC suspend group isHeld failed. lockName=${slotLock.lockName}, slot=${slotLock.slot}" }
            false
        }
}
