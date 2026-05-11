package io.bluetape4k.leader.zookeeper.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * ZooKeeper group elector 의 per-slot [org.apache.curator.framework.recipes.locks.Lease] 용 [ExtendDelegate]
 * — T13 PR 8 (Issue #79).
 *
 * ## 동작/계약 (PASSTHROUGH — Spec §6 row 12)
 *
 * ZooKeeper [org.apache.curator.framework.recipes.locks.InterProcessSemaphoreV2] 의 `Lease` 는
 * TTL 이 없는 ephemeral znode 입니다. `Lease.close()` 또는 세션 종료 시에만 반납됩니다.
 *
 * - [extend] / [extendSuspend] : delegate 가 살아있는 동안 (= handle 이 scope 에 push 된 동안)
 *   [ExtendOutcome.Extended] (observedExpireAt = [Instant.MAX]) 반환. elector 가 finally 에서 [markReleased]
 *   호출 후에는 [ExtendOutcome.NotHeld] 반환 (defensive — handle pop 후 호출되는 race 방어).
 * - [isHeld] : [released] 상태 직접 위임. `true` = 아직 release 전.
 *
 * `Lease` 는 liveness query API 가 없어 elector 가 explicit 하게 [markReleased] 로 상태 전이 통지.
 *
 * ## R16 enforce
 * Group elector 는 항상 `autoExtend=false` (옵션 부재) — watchdog 비활성화.
 * 이 delegate 의 [extend] 는 user-driven `LockExtender.extendActiveLock` 경로에서만 호출됩니다.
 */
internal class ZooKeeperSlotExtendDelegate(
    private val slotKey: String,
): ExtendDelegate {

    companion object: KLogging()

    private val released = AtomicBoolean(false)

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    /**
     * Elector 가 lease.close() 직전에 호출하여 delegate 를 NotHeld 상태로 전이합니다.
     *
     * race 방어 (handle pop 과 delegate state 동기화):
     * - lease.close() 이후에도 user 가 보유한 handle reference 로 extend 호출 시 NotHeld 반환.
     */
    fun markReleased() {
        released.set(true)
    }

    override fun extend(lockAtMostFor: Duration): ExtendOutcome = doExtend()

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome = doExtend()

    override fun isHeld(): Boolean = !released.get()

    private fun doExtend(): ExtendOutcome =
        try {
            if (released.get()) ExtendOutcome.NotHeld
            else ExtendOutcome.Extended(Instant.MAX)
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeper group extend (passthrough) failed. slotKey=$slotKey" }
            ExtendOutcome.BackendError(e)
        }
}
