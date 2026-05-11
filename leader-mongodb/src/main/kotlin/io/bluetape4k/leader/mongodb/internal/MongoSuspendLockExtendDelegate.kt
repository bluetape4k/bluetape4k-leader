package io.bluetape4k.leader.mongodb.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.mongodb.lock.MongoSuspendLock
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * [MongoSuspendLock] (suspend native, reactive coroutine driver) 용 [ExtendDelegate] — T9 PR 4 (Issue #79).
 *
 * ## 동작/계약
 * - MongoDB coroutine driver (`com.mongodb.kotlin.client.coroutine.MongoCollection`) 는 reactive native → suspend
 * - [extend] (sync) : suspend 함수만 노출되므로 `runBlocking` 으로 bridge.
 *   **production sync path 에서 호출 금지** — suspend 진입 후 [extendSuspend] 사용.
 *   AOP aspect 가 suspend wrapper 에서만 호출.
 * - [extendSuspend] : `lock.extendDetailed(d)` 직접 호출 (suspend native — withContext(IO) 불필요)
 * - [isHeld] : suspend 함수 → `runBlocking` bridge (rare path)
 *
 * Token-based 락이므로 thread 종속성 없음 (MongoDB 는 WrongThread 사용 안 함).
 */
internal class MongoSuspendLockExtendDelegate(
    private val lock: MongoSuspendLock,
) : ExtendDelegate {

    companion object : KLoggingChannel()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    /**
     * sync entry point — watchdog 의 scheduler thread 에서 호출됨.
     *
     * MongoDB coroutine driver 는 reactive 기반이므로 `runBlocking` 은 backpressure 없이 안전.
     * 그러나 suspend wrapper ([extendSuspend]) 가 우선 사용 권장.
     */
    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            runBlocking { lock.extendDetailed(lockAtMostFor) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "MongoSuspend extend failed. lockKey=${lock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            lock.extendDetailed(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "MongoSuspend extendSuspend failed. lockKey=${lock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    override fun isHeld(): Boolean =
        try {
            runBlocking { lock.isHeldByCurrentInstance() }
        } catch (e: Exception) {
            log.warn(e) { "MongoSuspend isHeld failed. lockKey=${lock.lockKey}" }
            false
        }
}
