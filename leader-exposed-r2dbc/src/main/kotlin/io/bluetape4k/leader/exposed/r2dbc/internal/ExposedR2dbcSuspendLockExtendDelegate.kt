package io.bluetape4k.leader.exposed.r2dbc.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.exposed.r2dbc.lock.ExposedR2dbcLock
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * [ExposedR2dbcLock] (suspend native, reactive R2DBC driver) 용 [ExtendDelegate] — T11 PR 6 (Issue #79).
 *
 * ## 동작/계약
 * - Exposed R2DBC 는 reactive native → `suspendTransaction(db)` 가 suspend
 * - [extend] (sync) : suspend `extendDetailed` 만 노출되므로 `runBlocking` bridge.
 *   주로 watchdog scheduler thread 에서 호출됨 — R2DBC 는 reactive 기반이라 backpressure 없이 안전.
 *   production sync path 에서 직접 호출 금지 — AOP aspect 는 suspend wrapper 우선 사용.
 * - [extendSuspend] : `lock.extendDetailed(d)` 직접 호출 (suspend native — `withContext(IO)` 불필요)
 * - [isHeld] : suspend 함수 → `runBlocking` bridge (rare path)
 *
 * Token-based 락이므로 thread 종속성 없음 (Exposed R2DBC 는 [ExtendOutcome.WrongThread] 사용 안 함).
 *
 * ## CancellationException 처리
 * 모든 `catch(Exception)` 앞에 `catch(CancellationException) { throw e }` 우선 처리 — 코루틴 취소 계약 보장.
 */
internal class ExposedR2dbcSuspendLockExtendDelegate(
    private val lock: ExposedR2dbcLock,
): ExtendDelegate {

    companion object: KLoggingChannel()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    /**
     * sync entry point — watchdog scheduler thread 에서 호출됨.
     *
     * Exposed R2DBC 는 reactive native 이므로 `runBlocking` 은 backpressure 없이 안전.
     * AOP aspect 의 suspend wrapper ([extendSuspend]) 우선 사용 권장.
     */
    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            runBlocking { lock.extendDetailed(lockAtMostFor) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Exposed R2DBC suspend extend failed. lockName=${lock.lockName}" }
            ExtendOutcome.BackendError(e)
        }

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            lock.extendDetailed(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Exposed R2DBC suspend extendSuspend failed. lockName=${lock.lockName}" }
            ExtendOutcome.BackendError(e)
        }

    override fun isHeld(): Boolean =
        try {
            runBlocking { lock.isHeldByCurrentInstance() }
        } catch (e: Exception) {
            log.warn(e) { "Exposed R2DBC suspend isHeld failed. lockName=${lock.lockName}" }
            false
        }
}
