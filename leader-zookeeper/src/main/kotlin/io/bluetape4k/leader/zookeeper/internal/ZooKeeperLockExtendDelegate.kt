package io.bluetape4k.leader.zookeeper.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.locks.InterProcessMutex
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * `ZooKeeperLockExtendDelegate`는 ZooKeeper 단일 lock의 lease 연장과 현재 ownership 확인을 elector에서 분리해 위임합니다.
 *
 * @property client 현재 ephemeral lock node가 살아 있는지 확인할 Curator client입니다.
 * @property mutex 이 process가 `InterProcessMutex`를 보유했는지 확인할 Curator mutex입니다.
 * @property lockKey 논리적 election path이며 진단용 lock identity입니다.
 * @property lockPath 현재 owner가 획득한 정확한 ephemeral node path입니다.
 */
internal class ZooKeeperLockExtendDelegate(
    private val client: CuratorFramework,
    private val mutex: InterProcessMutex,
    private val lockKey: String,
    private val lockPath: String,
): ExtendDelegate {

    companion object: KLogging()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override fun extend(lockAtMostFor: Duration): ExtendOutcome = doExtend()

    /** ZooKeeper에는 TTL 연장이 없으므로 live ownership만 확인해 `Extended(Instant.MAX)` 또는 `NotHeld`를 반환합니다. */
    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome = doExtend()

    override fun isHeld(): Boolean =
        try {
            isBackendHeld()
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeper isHeld failed. lockKey=$lockKey" }
            false
        }

    private fun doExtend(): ExtendOutcome =
        try {
            if (isBackendHeld()) ExtendOutcome.Extended(Instant.MAX)
            else ExtendOutcome.NotHeld
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeper extend (passthrough) failed. lockKey=$lockKey" }
            ExtendOutcome.BackendError(e)
        }

    private fun isBackendHeld(): Boolean =
        mutex.isAcquiredInThisProcess && ZooKeeperOwnershipProbe.isLiveNode(client, lockPath)
}
