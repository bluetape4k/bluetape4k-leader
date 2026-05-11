package io.bluetape4k.leader.hazelcast.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.hazelcast.lock.HazelcastSuspendLock
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * [HazelcastSuspendLock] 용 [ExtendDelegate] — T12 PR 7 (Issue #79).
 *
 * ## 동작/계약
 * - Hazelcast IMap 은 native blocking API — suspend lock 은 `withContext(Dispatchers.IO)` wrapper 만 추가
 * - [extend] (sync) : suspend 함수만 노출되므로 `runBlocking` bridge.
 *   **production sync path 에서 호출 금지** — watchdog scheduler thread 에서만 호출됨.
 * - [extendSuspend] : `lock.extendDetailed(d)` 직접 호출 (lock 이 이미 `withContext(IO)` 처리)
 * - [isHeld] : suspend 함수 → `runBlocking` bridge
 *
 * Token-based 락이므로 thread 종속성 없음.
 */
internal class HazelcastSuspendLockExtendDelegate(
    private val lock: HazelcastSuspendLock,
) : ExtendDelegate {

    companion object : KLoggingChannel()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    /**
     * sync entry point — watchdog 의 scheduler thread 에서 호출됨.
     *
     * `runBlocking` bridge — suspend wrapper ([extendSuspend]) 가 우선 사용 권장.
     */
    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            runBlocking { lock.extendDetailed(lockAtMostFor) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "HazelcastSuspend extend failed. lockKey=${lock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            lock.extendDetailed(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "HazelcastSuspend extendSuspend failed. lockKey=${lock.lockKey}" }
            ExtendOutcome.BackendError(e)
        }

    override fun isHeld(): Boolean =
        try {
            runBlocking { lock.isHeldByCurrentInstance() }
        } catch (e: Exception) {
            log.warn(e) { "HazelcastSuspend isHeld failed. lockKey=${lock.lockKey}" }
            false
        }
}
