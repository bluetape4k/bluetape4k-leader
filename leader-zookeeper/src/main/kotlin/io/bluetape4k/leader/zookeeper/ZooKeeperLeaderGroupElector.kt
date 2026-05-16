package io.bluetape4k.leader.zookeeper

import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.zookeeper.internal.ZooKeeperBackendErrorClassifier
import io.bluetape4k.leader.zookeeper.internal.ZooKeeperSlotExtendDelegate
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requirePositiveNumber
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreV2
import org.apache.curator.framework.recipes.locks.Lease
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * ZooKeeper multi-leader election implementation based on Apache Curator [InterProcessSemaphoreV2].
 *
 * ## Behavior / Contract
 * - Allows at most [maxLeaders] concurrent leases for the same [lockName].
 * - Returns `null` without executing [action] if lease acquisition fails.
 * - The acquired [Lease] is always `close()`d in `finally`.
 *
 * ## ExtendDelegate Integration (T13 PR 8 / Issue #79)
 *
 * - Creates a [ZooKeeperSlotExtendDelegate] for the acquired per-slot lease — shares the same reference with [LeaderLockHandle.Real] (AC-15).
 * - sync group: pushed to both `withPushedSync(handle)` + `setCapture(handle)`.
 * - ZooKeeper group leases are also ephemeral znode-based with no TTL — passthrough extend.
 *
 * ## R16 enforce
 *
 * [LeaderLeaseAutoExtender.start] always uses `enabled=false` (group options have no autoExtend).
 *
 * ```kotlin
 * val elector = ZooKeeperLeaderGroupElector(curator, LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = elector.runIfLeader("batch-job") { processChunk() }
 * ```
 *
 * @param client A started [CuratorFramework] client. Lifecycle management is the caller's responsibility.
 * @param basePath Base path where leader group znodes will be created
 * @param options Leader group election options
 */
class ZooKeeperLeaderGroupElector private constructor(
    private val client: CuratorFramework,
    private val basePath: String,
    options: LeaderGroupElectionOptions,
): LeaderGroupElector {

    companion object: KLogging() {
        const val DEFAULT_BASE_PATH = "/leader-group-election"
        internal const val ZOOKEEPER_GROUP_FACTORY_BEAN_NAME = "zookeeper-leader-group-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(ZooKeeperBackendErrorClassifier)

        @JvmStatic
        operator fun invoke(
            client: CuratorFramework,
            options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
            basePath: String = DEFAULT_BASE_PATH,
        ): ZooKeeperLeaderGroupElector {
            options.maxLeaders.requirePositiveNumber("maxLeaders")
            return ZooKeeperLeaderGroupElector(client, basePath, options)
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
            log.warn(e) { "ZooKeeper group participant 조회 실패. lockName=$lockName" }
            0
        }
    }

    override fun availableSlots(lockName: String): Int =
        maxLeaders - activeCount(lockName)

    override fun state(lockName: String): LeaderGroupState =
        LeaderGroupState(lockName, maxLeaders, activeCount(lockName))

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        val path = ZooKeeperPaths.electionPath(basePath, lockName)
        val semaphore = InterProcessSemaphoreV2(client, path, maxLeaders)

        log.debug { "ZooKeeper group lease 획득을 요청합니다. path=$path, maxLeaders=$maxLeaders" }
        val lease = try {
            semaphore.acquire(waitTime.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn(e) { "ZooKeeper group lease 획득 중 interrupt. path=$path" }
            return null
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeper group lease 획득 실패. path=$path" }
            return null
        } ?: return null

        log.debug { "ZooKeeper group lease 획득 성공. path=$path" }

        val acquiredAtNanos = System.nanoTime()
        val slotKey = path
        val delegate = ZooKeeperSlotExtendDelegate(slotKey)
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.GROUP,
            factoryBeanName = ZOOKEEPER_GROUP_FACTORY_BEAN_NAME,
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

        return try {
            AopScopeAccess.withPushedSync(handle) {
                AopScopeAccess.setCapture(handle)
                try {
                    action()
                } finally {
                    AopScopeAccess.clearCapture()
                }
            }
        } finally {
            try {
                watchdog.close()
            } catch (e: Exception) {
                log.warn(e) { "ZooKeeper group watchdog close 실패. path=$path" }
            }
            // delegate state 전이 (handle release 전): extend 호출 시 NotHeld 반환
            delegate.markReleased()
            try {
                lease.close()
                log.debug { "ZooKeeper group lease 반납 완료. path=$path" }
            } catch (e: Exception) {
                log.warn(e) { "ZooKeeper group lease 반납 실패. path=$path" }
            }
        }
    }

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        CompletableFuture.supplyAsync(
            { runIfLeader(lockName) { action().join() } },
            executor
        )

    private fun semaphore(lockName: String): InterProcessSemaphoreV2 =
        InterProcessSemaphoreV2(client, ZooKeeperPaths.electionPath(basePath, lockName), maxLeaders)
}

/**
 * Runs a multi-leader election action using a ZooKeeper [CuratorFramework].
 */
inline fun <T> CuratorFramework.runIfLeaderGroup(
    lockName: String,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    basePath: String = ZooKeeperLeaderGroupElector.DEFAULT_BASE_PATH,
    crossinline action: () -> T,
): T? =
    ZooKeeperLeaderGroupElector(this, options, basePath).runIfLeader(lockName) { action() }

/**
 * Runs an async multi-leader election action using a ZooKeeper [CuratorFramework].
 */
fun <T> CuratorFramework.runAsyncIfLeaderGroup(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    basePath: String = ZooKeeperLeaderGroupElector.DEFAULT_BASE_PATH,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    ZooKeeperLeaderGroupElector(this, options, basePath).runAsyncIfLeader(lockName, executor, action)
