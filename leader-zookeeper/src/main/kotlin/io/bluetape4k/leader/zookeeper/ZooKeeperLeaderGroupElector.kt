package io.bluetape4k.leader.zookeeper

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
 * `ZooKeeperLeaderGroupElector`는 ZooKeeper backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property client ZooKeeper backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property basePath ZooKeeper backend 호출과 상태 계산에 사용하는 속성입니다.
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

    override fun availableSlots(lockName: String): Int =
        maxLeaders - activeCount(lockName)

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
    ): CompletableFuture<T?> =
        CompletableFuture.supplyAsync(
            { runIfLeader(lockName) { action().join() } },
            executor
        )

    private fun semaphore(lockName: String): InterProcessSemaphoreV2 =
        InterProcessSemaphoreV2(client, ZooKeeperPaths.electionPath(basePath, lockName), maxLeaders)
}

/**
 * `선언` 호출은 ZooKeeper backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
inline fun <T> CuratorFramework.runIfLeaderGroup(
    path: ZooKeeperElectionPath,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    crossinline action: () -> T,
): T? =
    ZooKeeperLeaderGroupElector(this, options, path.basePath).runIfLeader(path.lockName) { action() }

/**
 * `선언` 호출은 ZooKeeper backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
inline fun <T> CuratorFramework.runIfLeaderGroup(
    lockName: String,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    basePath: String = ZooKeeperLeaderGroupElector.DEFAULT_BASE_PATH,
    crossinline action: () -> T,
): T? =
    runIfLeaderGroup(ZooKeeperElectionPath(lockName, basePath), options, action)

/**
 * `선언` 호출은 ZooKeeper backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
fun <T> CuratorFramework.runAsyncIfLeaderGroup(
    path: ZooKeeperElectionPath,
    executor: Executor = VirtualThreadExecutor,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    ZooKeeperLeaderGroupElector(this, options, path.basePath).runAsyncIfLeader(path.lockName, executor, action)

/**
 * `선언` 호출은 ZooKeeper backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
fun <T> CuratorFramework.runAsyncIfLeaderGroup(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    basePath: String = ZooKeeperLeaderGroupElector.DEFAULT_BASE_PATH,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    runAsyncIfLeaderGroup(ZooKeeperElectionPath(lockName, basePath), executor, options, action)
