package io.bluetape4k.leader.exposed.jdbc.internal

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcLock
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
 * `ExposedJdbcLockExtendDelegate`는 단일 JDBC lock의 lease 연장과 현재 owner 확인을 watchdog에 제공합니다.
 *
 * @property lock 단일 JDBC 락의 lease 연장·소유권 조회를 위임받는 실제 락 객체입니다.
 */
internal class ExposedJdbcLockExtendDelegate(
    private val lock: ExposedJdbcLock,
): ExtendDelegate {

    companion object: KLogging()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            lock.extendDetailed(lockAtMostFor)
        } catch (e: Exception) {
            log.warn(e) { "Exposed JDBC extend failed. lockName=${lock.lockName}" }
            ExtendOutcome.BackendError(e)
        }

    /**
     * JDBC blocking lease 연장을 IO dispatcher에서 수행합니다.
     * 코루틴 취소는 그대로 전파하고, 그 밖의 DB 오류는 [ExtendOutcome.BackendError]로 반환합니다.
     */
    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        try {
            lock.extendDetailed(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Exposed JDBC extendSuspend failed. lockName=${lock.lockName}" }
            ExtendOutcome.BackendError(e)
        }
    }

    override fun isHeld(): Boolean =
        try {
            lock.isHeldByCurrentInstance()
        } catch (e: Exception) {
            log.warn(e) { "Exposed JDBC isHeld failed. lockName=${lock.lockName}" }
            false
        }

    override fun toString(): String {
        return ToStringBuilder(this)
            .add("lock", lock)
            .toString()
    }
}
