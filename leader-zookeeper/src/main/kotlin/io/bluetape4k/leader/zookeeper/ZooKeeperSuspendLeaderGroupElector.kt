package io.bluetape4k.leader.zookeeper

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.zookeeper.internal.ZooKeeperBackendErrorClassifier
import io.bluetape4k.leader.zookeeper.internal.ZooKeeperSuspendSlotExtendDelegate
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requirePositiveNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreV2
import java.util.concurrent.TimeUnit

/**
 * ZooKeeper suspend multi-leader election implementation based on Apache Curator [InterProcessSemaphoreV2].
 *
 * ## Behavior / Contract
 * - Allows at most [maxLeaders] concurrent leases for the same [lockName].
 * - Returns `null` without executing [action] if lease acquisition fails.
 * - Even during cancellation, releases the lease inside `withContext(NonCancellable)` and re-propagates [CancellationException].
 *
 * ## ExtendDelegate Integration (T13 PR 8 / Issue #79)
 *
 * - Wraps the acquired per-slot lease with [ZooKeeperSuspendSlotExtendDelegate] — shares the same reference with [LeaderLockHandle.Real] (AC-15).
 * - suspend group: propagates handle to coroutineContext via `withContext(createLockHandleElement(handle))`.
 *
 * ## R16 enforce
 *
 * [LeaderLeaseAutoExtender.start] always uses `enabled=false` — ZooKeeper has no TTL.
 *
 * ```kotlin
 * val elector = ZooKeeperSuspendLeaderGroupElector(curator, LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = elector.runIfLeader("batch-job") { processChunk() }
 * ```
 *
 * @param client A started [CuratorFramework] client. Lifecycle management is the caller's responsibility.
 * @param basePath Base path where leader group znodes will be created
 * @param options Leader group election options
 */
class ZooKeeperSuspendLeaderGroupElector private constructor(
    private val client: CuratorFramework,
    private val basePath: String,
    options: LeaderGroupElectionOptions,
): SuspendLeaderGroupElector {

    companion object: KLoggingChannel() {
        const val DEFAULT_BASE_PATH = "/leader-group-election"
        internal const val ZOOKEEPER_SUSPEND_GROUP_FACTORY_BEAN_NAME = "zookeeper-suspend-leader-group-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(ZooKeeperBackendErrorClassifier)

        @JvmStatic
        operator fun invoke(
            client: CuratorFramework,
            options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
            basePath: String = DEFAULT_BASE_PATH,
        ): ZooKeeperSuspendLeaderGroupElector {
            options.maxLeaders.requirePositiveNumber("maxLeaders")
            return ZooKeeperSuspendLeaderGroupElector(client, basePath, options)
        }
    }

    override val maxLeaders: Int = options.maxLeaders
    private val waitTime = options.waitTime
    private val leaseTime = options.leaseTime

    override fun activeCount(lockName: String): Int {
        val semaphore = semaphore(lockName)
        return try {
            semaphore.participantNodes.size.coerceAtMost(maxLeaders)
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeper suspend group participant 조회 실패. lockName=$lockName" }
            0
        }
    }

    override fun availableSlots(lockName: String): Int =
        maxLeaders - activeCount(lockName)

    override fun state(lockName: String): LeaderGroupState =
        LeaderGroupState(lockName, maxLeaders, activeCount(lockName))

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        val path = ZooKeeperPaths.electionPath(basePath, lockName)
        val semaphore = InterProcessSemaphoreV2(client, path, maxLeaders)

        log.debug { "ZooKeeper suspend group lease 획득을 요청합니다. path=$path, maxLeaders=$maxLeaders" }
        val lease = try {
            withContext(Dispatchers.IO) {
                semaphore.acquire(waitTime.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeper suspend group lease 획득 실패. path=$path" }
            return null
        } ?: return null

        log.debug { "ZooKeeper suspend group lease 획득 성공. path=$path" }

        val acquiredAtNanos = System.nanoTime()
        val slotKey = path
        val delegate = ZooKeeperSuspendSlotExtendDelegate(slotKey)
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.GROUP,
            factoryBeanName = ZOOKEEPER_SUSPEND_GROUP_FACTORY_BEAN_NAME,
            groupParams = LockIdentity.GroupParams(maxLeaders),
        )
        val handle = LeaderLockHandle.real(
            identity = identity,
            token = slotKey,
            acquiredAtNanos = acquiredAtNanos,
            slotId = lease.nodeName,
            extendDelegate = delegate,
        )
        // R16 enforce: ZK 는 TTL 없음 — group 도 autoExtend=false (옵션 자체가 없음)
        val watchdog = LeaderLeaseAutoExtender.start(
            enabled = false,
            leaseTime = leaseTime,
            delegate = delegate,
            classifier = ERROR_CLASSIFIER,
        )

        try {
            return withContext(AopScopeAccess.createLockHandleElement(handle)) {
                action()
            }
        } finally {
            // NonCancellable: 코루틴 취소 시에도 watchdog close + release 가 중단되지 않도록 보호
            withContext(NonCancellable) {
                try {
                    watchdog.close()
                } catch (e: Exception) {
                    log.warn(e) { "ZooKeeper suspend group watchdog close 실패. path=$path" }
                }
                delegate.markReleased()
                try {
                    withContext(Dispatchers.IO) {
                        lease.close()
                    }
                    log.debug { "ZooKeeper suspend group lease 반납 완료. path=$path" }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "ZooKeeper suspend group lease 반납 실패. path=$path" }
                }
            }
        }
    }

    private fun semaphore(lockName: String): InterProcessSemaphoreV2 =
        InterProcessSemaphoreV2(client, ZooKeeperPaths.electionPath(basePath, lockName), maxLeaders)
}

/**
 * Runs a suspend multi-leader election action using a ZooKeeper [CuratorFramework].
 */
suspend inline fun <T> CuratorFramework.suspendRunIfLeaderGroup(
    lockName: String,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    basePath: String = ZooKeeperSuspendLeaderGroupElector.DEFAULT_BASE_PATH,
    crossinline action: suspend () -> T,
): T? =
    ZooKeeperSuspendLeaderGroupElector(this, options, basePath).runIfLeader(lockName) { action() }
