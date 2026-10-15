package io.bluetape4k.leader.zookeeper

import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.internal.LeaderFutureBridge
import io.bluetape4k.leader.zookeeper.runAsyncIfLeaderGroup as currentRunAsyncIfLeaderGroup
import io.bluetape4k.leader.zookeeper.runIfLeaderGroup as currentRunIfLeaderGroup
import io.bluetape4k.leader.zookeeper.internal.ZooKeeperBackendErrorClassifier
import io.bluetape4k.leader.zookeeper.internal.ZooKeeperSlotExtendDelegate
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requirePositiveNumber
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreV2
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * `ZooKeeperLeaderGroupElector`는 ZooKeeper의 blocking 그룹 선출을 제공하며 `maxLeaders` 슬롯을 acquire·extend·release합니다.
 *
 * lock 또는 slot을 획득하지 못한 `runIfLeader` 호출은 예외 대신 `null`/skip 결과를 반환합니다.
 * @property client semaphore participant ephemeral node를 생성·조회·해제하는 `CuratorFramework`입니다.
 * @property basePath 그룹 lockName을 붙일 ZooKeeper root path이며 기본값은 `/leader-group-election`입니다.
 */
class ZooKeeperLeaderGroupElector private constructor(
    private val client: CuratorFramework,
    private val basePath: String,
    options: LeaderGroupElectionOptions,
): LeaderGroupElector,
   LeaderBackendDiagnosticsProvider by ZooKeeperLeaderBackendDiagnostics(client) {

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

    override fun availableSlots(lockName: String): Int = maxLeaders - activeCount(lockName)

    override fun state(lockName: String): LeaderGroupState =
        LeaderGroupState(lockName, maxLeaders, activeCount(lockName))

    // Cleanup must rethrow an interrupted release while restoring the flag, even from finally.
    @Suppress("ThrowingExceptionFromFinally", "LongMethod", "ReturnCount")
    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        val path = ZooKeeperPaths.electionPath(basePath, lockName)
        val semaphore = InterProcessSemaphoreV2(client, path, maxLeaders)

        log.debug { "ZooKeeper group lease 획득을 요청합니다. path=$path, maxLeaders=$maxLeaders" }

        val lease = try {
            semaphore.acquire(waitTime.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn(e) { "ZooKeeper group lease 획득 중 interrupt. path=$path" }
            throw e
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeper group lease 획득 실패. path=$path" }
            return null
        } ?: return null

        log.debug { "ZooKeeper group lease 획득 성공. path=$path" }

        val acquiredAtNanos = System.nanoTime()
        val slotKey = path
        val delegate = ZooKeeperSlotExtendDelegate(client, slotKey, lease.nodeName)
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
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                log.warn(e) { "ZooKeeper group lease 반납 중 interrupt. path=$path" }
                throw e
            } catch (e: Exception) {
                log.warn(e) { "ZooKeeper group lease 반납 실패. path=$path" }
            }
        }
    }

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val cancellationRelay = LeaderFutureBridge.cancellationRelay()
        return LeaderFutureBridge.propagateCancellation(
            futureOf(VirtualThreadExecutor) {
                runIfLeader(lockName) {
                    cancellationRelay.invoke { submitZooKeeperAction(lockName, executor, action) }.join()
                }
            },
            cancellationRelay,
        )
    }

    private fun semaphore(lockName: String): InterProcessSemaphoreV2 =
        InterProcessSemaphoreV2(client, ZooKeeperPaths.electionPath(basePath, lockName), maxLeaders)
}

/** 리팩터링 전 JVM facade의 바이너리 호환성을 보존하는 shim입니다. */
@Deprecated("리팩터링 전 JVM facade 호환성 유지용", level = DeprecationLevel.HIDDEN)
@JvmName("runIfLeaderGroup")
inline fun <T> CuratorFramework.legacyRunIfLeaderGroupByPath(
    path: ZooKeeperElectionPath,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    crossinline action: () -> T,
): T? = this.currentRunIfLeaderGroup(path, options, action)

/** Binary compatibility shim for the pre-refactor JVM facade. */
@Deprecated("Binary compatibility shim", level = DeprecationLevel.HIDDEN)
@JvmName("runIfLeaderGroup")
inline fun <T> CuratorFramework.legacyRunIfLeaderGroupByName(
    lockName: String,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    basePath: String = ZooKeeperLeaderGroupElector.DEFAULT_BASE_PATH,
    crossinline action: () -> T,
): T? = this.currentRunIfLeaderGroup(lockName, options, basePath, action)

/** Binary compatibility shim for the pre-refactor JVM facade. */
@Deprecated("Binary compatibility shim", level = DeprecationLevel.HIDDEN)
@JvmName("runAsyncIfLeaderGroup")
fun <T> CuratorFramework.legacyRunAsyncIfLeaderGroupByPath(
    path: ZooKeeperElectionPath,
    executor: Executor = VirtualThreadExecutor,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> = this.currentRunAsyncIfLeaderGroup(path, executor, options, action)

/** Binary compatibility shim for the pre-refactor JVM facade. */
@Deprecated("Binary compatibility shim", level = DeprecationLevel.HIDDEN)
@JvmName("runAsyncIfLeaderGroup")
fun <T> CuratorFramework.legacyRunAsyncIfLeaderGroupByName(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    basePath: String = ZooKeeperLeaderGroupElector.DEFAULT_BASE_PATH,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> = this.currentRunAsyncIfLeaderGroup(lockName, executor, options, basePath, action)
