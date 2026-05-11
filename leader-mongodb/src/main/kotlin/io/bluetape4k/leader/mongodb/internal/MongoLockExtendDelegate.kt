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
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

/**
 * [MongoLock] (sync, blocking driver) 용 [ExtendDelegate] — T9 PR 4 (Issue #79).
 *
 * ## 동작/계약
 * - [extend] : `lock.extendDetailed(d)` 결과를 그대로 반환. backend exception 은 [ExtendOutcome.BackendError] 로 wrap
 * - [extendSuspend] : MongoDB sync driver 는 blocking IO 이므로 `withContext(Dispatchers.IO)` + `ensureActive()` 로 wrap (R9 / AC-21)
 * - [isHeld] : `lock.isHeldByCurrentInstance()` 위임
 * - [lastExtendDeadline] : `AtomicReference(Instant.EPOCH)` 단일 인스턴스 보관 — R2 watchdog skip 용
 *
 * Token-based 락이므로 thread 종속성 없음 (MongoDB 는 WrongThread 사용 안 함).
 */
internal class MongoLockExtendDelegate(
    private val lock: MongoLock,
) : ExtendDelegate {

    companion object : KLogging()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            lock.extendDetailed(lockAtMostFor)
        } catch (e: Exception) {
            log.warn(e) { "MongoDB extend failed. lockKey=${lock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    /**
     * MongoDB sync driver 는 blocking — `withContext(Dispatchers.IO)` 로 dispatch.
     *
     * **runCatching {} 사용 금지** — suspend 안에서 CancellationException 을 삼킬 수 있음.
     * 수동 try/catch + `catch(CancellationException) { throw e }` 사용.
     */
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
