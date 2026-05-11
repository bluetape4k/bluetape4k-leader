package io.bluetape4k.leader.zookeeper.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import org.apache.curator.framework.recipes.locks.InterProcessMutex
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * ZooKeeper [InterProcessMutex] (sync, Curator) 용 [ExtendDelegate] — T13 PR 8 (Issue #79).
 *
 * ## 동작/계약 (PASSTHROUGH — Spec §6 row 12)
 *
 * ZooKeeper 는 TTL 개념이 없는 **세션 기반 락** 입니다. [InterProcessMutex] 는 ephemeral znode 로
 * 구현되며 ZK 세션이 살아있는 동안에만 락이 유효합니다. 따라서 `extend(d)` 는 lease 연장이 아닌
 * **session-held liveness 확인** 의미입니다 (R3-F11).
 *
 * - [extend] : `mutex.isAcquiredInThisProcess()` 가 `true` 이면 [ExtendOutcome.Extended] (observedExpireAt = [Instant.MAX])
 *   반환, `false` 이면 [ExtendOutcome.NotHeld] 반환. backend 예외는 [ExtendOutcome.BackendError] 로 wrap
 * - [extendSuspend] : `isAcquiredInThisProcess()` 는 로컬 카운터 체크 — non-blocking. `withContext(IO)` 불필요.
 *   CancellationException 재전파만 추가.
 * - [isHeld] : `mutex.isAcquiredInThisProcess()` 직접 위임
 * - [lastExtendDeadline] : `AtomicReference(Instant.EPOCH)` 단일 인스턴스 (R2 mitigation 인터페이스 정합 — ZK 에서는
 *   watchdog 가 비활성화되어 cosmetic 이지만 [LockExtender.extendActiveLockDetailed] 가 set 호출함)
 *
 * Token-based 락 (session-bound) — thread 종속성 없음 (Redisson 처럼 WrongThread 사용 안 함).
 *
 * ## R16 enforce
 * Elector 가 [io.bluetape4k.leader.LeaderLeaseAutoExtender.start] 호출 시 `enabled=false` 강제.
 * 이 delegate 의 [extend] 는 user-driven `LockExtender.extendActiveLock` 경로에서만 호출됩니다.
 */
internal class ZooKeeperLockExtendDelegate(
    private val mutex: InterProcessMutex,
    private val lockKey: String,
): ExtendDelegate {

    companion object: KLogging()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override fun extend(lockAtMostFor: Duration): ExtendOutcome = doExtend()

    /**
     * `isAcquiredInThisProcess()` 는 Curator 의 로컬 카운터 — non-blocking. `withContext(IO)` 불필요.
     */
    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome = doExtend()

    override fun isHeld(): Boolean =
        try {
            mutex.isAcquiredInThisProcess
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeper isHeld failed. lockKey=$lockKey" }
            false
        }

    private fun doExtend(): ExtendOutcome =
        try {
            if (mutex.isAcquiredInThisProcess) ExtendOutcome.Extended(Instant.MAX)
            else ExtendOutcome.NotHeld
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeper extend (passthrough) failed. lockKey=$lockKey" }
            ExtendOutcome.BackendError(e)
        }
}
