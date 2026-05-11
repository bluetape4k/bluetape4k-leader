package io.bluetape4k.leader.lettuce.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.lettuce.lock.LettuceSuspendLock
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * [LettuceSuspendLock] (suspend native) 용 [ExtendDelegate] — T7 PR 2.
 *
 * ## 동작/계약
 * - Lettuce async API 는 Netty event-loop 기반 non-blocking → suspend native
 * - [extend] (sync) : suspend 함수만 노출되므로 `runBlocking` 으로 bridge. **production sync path 에서 호출 금지** —
 *   suspend 진입 후 [extendSuspend] 사용. AOP aspect 가 suspend wrapper 에서만 호출.
 * - [extendSuspend] : `lock.extendDetailed(d)` 직접 호출 (suspend native — withContext(IO) 불필요)
 * - [isHeld] : suspend 함수 → `runBlocking` bridge (rare path)
 */
internal class LettuceSuspendLockExtendDelegate(
    private val lock: LettuceSuspendLock,
) : ExtendDelegate {

    companion object : KLoggingChannel()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    /**
     * sync entry point — watchdog 의 scheduler thread 에서 호출됨.
     *
     * Lettuce async API 는 Netty 기반이므로 `runBlocking` 은 backpressure 없이 안전.
     * 그러나 suspend wrapper ([extendSuspend]) 가 우선 사용 권장.
     */
    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            runBlocking { lock.extendDetailed(lockAtMostFor) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "LettuceSuspend extend failed. lockKey=${lock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            lock.extendDetailed(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "LettuceSuspend extendSuspend failed. lockKey=${lock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    override fun isHeld(): Boolean =
        try {
            runBlocking { lock.isHeldByCurrentInstance() }
        } catch (e: Exception) {
            log.warn(e) { "LettuceSuspend isHeld failed. lockKey=${lock.lockKey}" }
            false
        }
}
