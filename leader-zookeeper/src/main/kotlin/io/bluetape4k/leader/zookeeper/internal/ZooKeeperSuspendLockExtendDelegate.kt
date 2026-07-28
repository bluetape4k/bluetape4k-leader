package io.bluetape4k.leader.zookeeper.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.locks.InterProcessMutex
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * `ZooKeeperSuspendLockExtendDelegate`는 ZooKeeper backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property client ZooKeeper backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property mutex ZooKeeper backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property lockKey ZooKeeper backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property lockPath ZooKeeper backend 호출과 상태 계산에 사용하는 속성입니다.
 */
internal class ZooKeeperSuspendLockExtendDelegate(
    private val client: CuratorFramework,
    private val mutex: InterProcessMutex,
    private val lockKey: String,
    private val lockPath: String,
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
            isBackendHeld()
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeperSuspend isHeld failed. lockKey=$lockKey" }
            false
        }

    private fun doExtend(): ExtendOutcome =
        try {
            if (isBackendHeld()) ExtendOutcome.Extended(Instant.MAX)
            else ExtendOutcome.NotHeld
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeperSuspend extend (passthrough) failed. lockKey=$lockKey" }
            ExtendOutcome.BackendError(e)
        }

    private fun isBackendHeld(): Boolean =
        mutex.isAcquiredInThisProcess && ZooKeeperOwnershipProbe.isLiveNode(client, lockPath)
}
