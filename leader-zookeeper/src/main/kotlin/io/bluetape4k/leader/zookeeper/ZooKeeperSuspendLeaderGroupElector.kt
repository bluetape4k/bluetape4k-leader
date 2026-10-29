package io.bluetape4k.leader.zookeeper

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
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
import io.bluetape4k.leader.zookeeper.suspendRunIfLeaderGroup as currentSuspendRunIfLeaderGroup

/**
 * `ZooKeeperSuspendLeaderGroupElector`는 ZooKeeper의 suspend 그룹 선출을 제공하며 `maxLeaders` 슬롯을 acquire·extend·release합니다.
 *
 * lock 또는 slot을 획득하지 못한 `runIfLeader` 호출은 예외 대신 `null`/skip 결과를 반환합니다.
 * @property client semaphore participant ephemeral node를 생성·조회·해제하는 `CuratorFramework`입니다.
 * @property basePath 그룹 lockName을 붙일 ZooKeeper root path이며 기본값은 `/leader-group-election`입니다.
 */
class ZooKeeperSuspendLeaderGroupElector private constructor(
    private val client: CuratorFramework,
    private val basePath: String,
    options: LeaderGroupElectionOptions,
): SuspendLeaderGroupElector,
   LeaderBackendDiagnosticsProvider by ZooKeeperLeaderBackendDiagnostics(client) {

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

    @Suppress("TooGenericExceptionCaught")
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

    @Suppress("TooGenericExceptionCaught")
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
            null
        } ?: return null

        log.debug { "ZooKeeper suspend group lease 획득 성공. path=$path" }

        val acquiredAtNanos = System.nanoTime()
        val slotKey = path
        val delegate = ZooKeeperSuspendSlotExtendDelegate(client, slotKey, lease.nodeName)
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
            releaseAfterRun(path, lease, watchdog, delegate)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun releaseAfterRun(
        path: String,
        lease: org.apache.curator.framework.recipes.locks.Lease,
        watchdog: AutoCloseable,
        delegate: ZooKeeperSuspendSlotExtendDelegate,
    ) {
        // NonCancellable: 코루틴 취소 시에도 watchdog close + release 가 중단되지 않도록 보호
        withContext(NonCancellable) {
            try {
                LeaderLeaseAutoExtender.closeSuspend(watchdog)
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

    private fun semaphore(lockName: String): InterProcessSemaphoreV2 =
        InterProcessSemaphoreV2(client, ZooKeeperPaths.electionPath(basePath, lockName), maxLeaders)
}

/** 리팩터링 전 JVM facade의 바이너리 호환성을 보존하는 shim입니다. */
@Deprecated("리팩터링 전 JVM facade 호환성 유지용", level = DeprecationLevel.HIDDEN)
@JvmName("suspendRunIfLeaderGroup")
suspend inline fun <T> CuratorFramework.legacySuspendRunIfLeaderGroupByPath(
    path: ZooKeeperElectionPath,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? = this.currentSuspendRunIfLeaderGroup(path, options, action)

/** Binary compatibility shim for the pre-refactor JVM facade. */
@Deprecated("Binary compatibility shim", level = DeprecationLevel.HIDDEN)
@JvmName("suspendRunIfLeaderGroup")
suspend inline fun <T> CuratorFramework.legacySuspendRunIfLeaderGroupByName(
    lockName: String,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    basePath: String = ZooKeeperSuspendLeaderGroupElector.DEFAULT_BASE_PATH,
    crossinline action: suspend () -> T,
): T? = this.currentSuspendRunIfLeaderGroup(lockName, options, basePath, action)
