package io.bluetape4k.leader.zookeeper.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import org.apache.curator.framework.CuratorFramework
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * `ZooKeeperSlotExtendDelegate`는 ZooKeeper 그룹 slot/permit의 lease 연장과 현재 ownership 확인을 elector에서 분리해 위임합니다.
 *
 * @property client 그룹 lease의 participant ephemeral node가 살아 있는지 확인할 Curator client입니다.
 * @property slotKey semaphore가 participant node를 만드는 그룹 path입니다.
 */
internal class ZooKeeperSlotExtendDelegate(
    private val client: CuratorFramework,
    private val slotKey: String,
    leaseNodeName: String,
): ExtendDelegate {

    companion object: KLogging()

    private val leasePath = ZooKeeperOwnershipProbe.leaseNodePath(slotKey, leaseNodeName)
    private val released = AtomicBoolean(false)

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    /** lease close 전에 로컬 released flag를 세워 이후 연장·보유 조회를 `NotHeld`로 전환합니다. */
    fun markReleased() {
        released.set(true)
    }

    override fun extend(lockAtMostFor: Duration): ExtendOutcome = doExtend()

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome = doExtend()

    override fun isHeld(): Boolean =
        try {
            isBackendHeld()
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeper group isHeld failed. slotKey=$slotKey" }
            false
        }

    private fun doExtend(): ExtendOutcome =
        try {
            if (isBackendHeld()) ExtendOutcome.Extended(Instant.MAX)
            else ExtendOutcome.NotHeld
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeper group extend (passthrough) failed. slotKey=$slotKey" }
            ExtendOutcome.BackendError(e)
        }

    private fun isBackendHeld(): Boolean =
        !released.get() && ZooKeeperOwnershipProbe.isLiveNode(client, leasePath)
}
