package io.bluetape4k.leader.exposed.jdbc.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcGroupLock
import io.bluetape4k.leader.internal.ExtendDelegate
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
 * `ExposedJdbcSlotExtendDelegate`는 JDBC group slot lock의 lease 연장과 현재 owner 확인을 watchdog에 제공합니다.
 *
 * @property slotLock 그룹 슬롯의 lease 연장·소유권 조회를 위임받는 실제 JDBC 슬롯 락 객체입니다.
 */
internal class ExposedJdbcSlotExtendDelegate(
    private val slotLock: ExposedJdbcGroupLock,
): ExtendDelegate {

    companion object: KLogging()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            slotLock.extendDetailed(lockAtMostFor)
        } catch (e: Exception) {
            log.warn(e) { "Exposed JDBC group extend failed. lockName=${slotLock.lockName}, slot=${slotLock.slot}" }
            ExtendOutcome.BackendError(e)
        }

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        try {
            slotLock.extendDetailed(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Exposed JDBC group extendSuspend failed. lockName=${slotLock.lockName}, slot=${slotLock.slot}" }
            ExtendOutcome.BackendError(e)
        }
    }

    override fun isHeld(): Boolean =
        try {
            slotLock.isHeldByCurrentInstance()
        } catch (e: Exception) {
            log.warn(e) { "Exposed JDBC group isHeld failed. lockName=${slotLock.lockName}, slot=${slotLock.slot}" }
            false
        }

    override fun toString(): String =
        "ExposedJdbcSlotExtendDelegate(lastExtendDeadline=$lastExtendDeadline)"
}
