package io.bluetape4k.leader.zookeeper.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import org.apache.curator.framework.recipes.locks.InterProcessMutex
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * ZooKeeper [InterProcessMutex] (suspend) 용 [ExtendDelegate] — T13 PR 8 (Issue #79).
 *
 * ## 동작/계약 (PASSTHROUGH — Spec §6 row 12)
 *
 * ZooKeeper 는 TTL 개념이 없는 **세션 기반 락** 입니다. `extend(d)` 는 lease 연장이 아닌
 * **session-held liveness 확인** 의미입니다 (R3-F11).
 *
 * - [extend] / [extendSuspend] : `mutex.isAcquiredInThisProcess()` (Curator 로컬 카운터 — non-blocking)
 *   결과에 따라 [ExtendOutcome.Extended] (observedExpireAt = [Instant.MAX]) 또는 [ExtendOutcome.NotHeld].
 * - [extendSuspend] 는 로컬 카운터 체크이므로 `withContext(IO)` 래핑 불필요 — CancellationException 만 재전파.
 * - [isHeld] : `isAcquiredInThisProcess` 직접 위임.
 *
 * Token-based 락 (session-bound) — thread 종속성 없음.
 *
 * ## R16 enforce
 * Elector 가 [io.bluetape4k.leader.LeaderLeaseAutoExtender.start] 호출 시 `enabled=false` 강제.
 */
internal class ZooKeeperSuspendLockExtendDelegate(
    private val mutex: InterProcessMutex,
    private val lockKey: String,
): ExtendDelegate {

    companion object: KLoggingChannel()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override fun extend(lockAtMostFor: Duration): ExtendOutcome = doExtend()

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            doExtend()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeperSuspend extendSuspend (passthrough) failed. lockKey=$lockKey" }
            ExtendOutcome.BackendError(e)
        }

    override fun isHeld(): Boolean =
        try {
            mutex.isAcquiredInThisProcess
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeperSuspend isHeld failed. lockKey=$lockKey" }
            false
        }

    private fun doExtend(): ExtendOutcome =
        try {
            if (mutex.isAcquiredInThisProcess) ExtendOutcome.Extended(Instant.MAX)
            else ExtendOutcome.NotHeld
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeperSuspend extend (passthrough) failed. lockKey=$lockKey" }
            ExtendOutcome.BackendError(e)
        }
}
