package io.bluetape4k.leader.zookeeper.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * ZooKeeper suspend group elector 의 per-slot [org.apache.curator.framework.recipes.locks.Lease] 용 [ExtendDelegate]
 * — T13 PR 8 (Issue #79).
 *
 * ## 동작/계약 (PASSTHROUGH — Spec §6 row 12)
 *
 * - [extend] / [extendSuspend] : delegate 가 살아있는 동안 [ExtendOutcome.Extended] (observedExpireAt = [Instant.MAX]) 반환.
 *   elector 가 finally 에서 [markReleased] 호출 후에는 [ExtendOutcome.NotHeld].
 * - [extendSuspend] 는 로컬 atomic 체크 — `withContext(IO)` 불필요.
 * - [isHeld] : [released] 상태 직접 위임.
 *
 * ## R16 enforce
 * Group elector 는 항상 `autoExtend=false` — watchdog 비활성화.
 */
internal class ZooKeeperSuspendSlotExtendDelegate(
    private val slotKey: String,
): ExtendDelegate {

    companion object: KLoggingChannel()

    private val released = AtomicBoolean(false)

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    fun markReleased() {
        released.set(true)
    }

    override fun extend(lockAtMostFor: Duration): ExtendOutcome = doExtend()

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            doExtend()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeperSuspend group extendSuspend (passthrough) failed. slotKey=$slotKey" }
            ExtendOutcome.BackendError(e)
        }

    override fun isHeld(): Boolean = !released.get()

    private fun doExtend(): ExtendOutcome =
        try {
            if (released.get()) ExtendOutcome.NotHeld
            else ExtendOutcome.Extended(Instant.MAX)
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeperSuspend group extend (passthrough) failed. slotKey=$slotKey" }
            ExtendOutcome.BackendError(e)
        }
}
