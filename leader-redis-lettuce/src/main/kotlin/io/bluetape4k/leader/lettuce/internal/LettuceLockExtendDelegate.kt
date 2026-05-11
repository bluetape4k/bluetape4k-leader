package io.bluetape4k.leader.lettuce.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.lettuce.lock.LettuceLock
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
 * [LettuceLock] (sync blocking) 용 [ExtendDelegate] — T7 PR 2.
 *
 * ## 동작/계약
 * - [extend] : `lock.extendDetailed(d)` 결과를 그대로 반환. backend exception 은 [ExtendOutcome.BackendError] 로 wrap
 * - [extendSuspend] : Lettuce sync 는 blocking IO 이므로 `withContext(Dispatchers.IO)` + `ensureActive()` 로 wrap (R9)
 * - [isHeld] : `lock.isHeldByCurrentInstance()` 위임
 * - [lastExtendDeadline] : `AtomicReference(Instant.EPOCH)` 단일 인스턴스 보관 — R2 watchdog skip 용
 *
 * AC-21: blocking backend 의 [ExtendDelegate.extendSuspend] 가 default 사용 0회 — 본 클래스가 명시적으로 override.
 */
internal class LettuceLockExtendDelegate(
    private val lock: LettuceLock,
) : ExtendDelegate {

    companion object : KLogging()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            lock.extendDetailed(lockAtMostFor)
        } catch (e: Exception) {
            log.warn(e) { "Lettuce extend failed. lockKey=${lock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    /**
     * Lettuce sync API 는 Netty client 의 blocking facade — `withContext(Dispatchers.IO)` 로 dispatch.
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
            log.warn(e) { "Lettuce extendSuspend failed. lockKey=${lock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }
    }

    override fun isHeld(): Boolean =
        try {
            lock.isHeldByCurrentInstance()
        } catch (e: Exception) {
            log.warn(e) { "Lettuce isHeld failed. lockKey=${lock.lockKey}" }
            false
        }
}
