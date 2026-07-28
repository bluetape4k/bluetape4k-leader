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
 * `ZooKeeperLockExtendDelegate`는 ZooKeeper backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property client ZooKeeper backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property mutex ZooKeeper backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property lockKey ZooKeeper backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property lockPath ZooKeeper backend 호출과 상태 계산에 사용하는 속성입니다.
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

    /**
     * `extendSuspend` 호출은 ZooKeeper backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
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
