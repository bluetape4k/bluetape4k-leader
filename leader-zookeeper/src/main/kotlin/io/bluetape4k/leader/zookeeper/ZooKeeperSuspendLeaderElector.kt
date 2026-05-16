package io.bluetape4k.leader.zookeeper

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.zookeeper.internal.ZooKeeperBackendErrorClassifier
import io.bluetape4k.leader.zookeeper.internal.ZooKeeperSuspendLockExtendDelegate
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.locks.InterProcessMutex
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ZooKeeper suspend single-leader election implementation based on Apache Curator [InterProcessMutex].
 *
 * ## Behavior / Contract
 * - Uses the same [InterProcessMutex] recipe as the blocking [ZooKeeperLeaderElector] to mutually exclude the same [lockName].
 * - Since the Curator mutex has a thread-owner constraint, acquire/release is performed on a per-call single-thread dispatcher.
 * - Even during cancellation, releases the lock inside `withContext(NonCancellable)` and re-propagates [CancellationException].
 * - [LeaderElectionOptions.leaseTime] is not used as a ZooKeeper TTL; session termination/expiry is the automatic release boundary.
 *
 * ## ExtendDelegate Integration (T13 PR 8 / Issue #79)
 *
 * - After acquire, creates a [ZooKeeperSuspendLockExtendDelegate] that shares the same reference with [LeaderLockHandle.Real] (AC-15).
 * - The aspect's `LockExtenderSuspend.extendActiveLockSuspend` uses the same delegate reference.
 * - Propagates handle to coroutineContext via `withContext(AopScopeAccess.createLockHandleElement(handle))`.
 *
 * ## R16 enforce — ZooKeeper has no TTL
 *
 * [LeaderLeaseAutoExtender.start] is **always forced to `enabled=false`**. A WARN log is emitted if the user sets `autoExtend=true`.
 *
 * ```kotlin
 * val elector = ZooKeeperSuspendLeaderElector(curator)
 * val result = elector.runIfLeader("sync-job") { syncData() }
 * ```
 *
 * @param client A started [CuratorFramework] client. Lifecycle management is the caller's responsibility.
 * @param basePath Base path where leader election znodes will be created
 * @param options Leader election options
 */
class ZooKeeperSuspendLeaderElector private constructor(
    private val client: CuratorFramework,
    private val basePath: String,
    private val options: LeaderElectionOptions,
): SuspendLeaderElector {

    companion object: KLoggingChannel() {
        const val DEFAULT_BASE_PATH = "/leader-election"
        internal const val ZOOKEEPER_SUSPEND_FACTORY_BEAN_NAME = "zookeeper-suspend-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(ZooKeeperBackendErrorClassifier)

        @JvmStatic
        operator fun invoke(
            client: CuratorFramework,
            basePath: String = DEFAULT_BASE_PATH,
            options: LeaderElectionOptions = LeaderElectionOptions.Default,
        ): ZooKeeperSuspendLeaderElector =
            ZooKeeperSuspendLeaderElector(client, basePath, options)
    }

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        val path = ZooKeeperPaths.electionPath(basePath, lockName)
        val mutex = InterProcessMutex(client, path)
        val ownerDispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "zookeeper-suspend-leader-$lockName").apply {
                isDaemon = true
            }
        }.asCoroutineDispatcher()

        try {
            log.debug { "ZooKeeper suspend leader lock 획득을 요청합니다. path=$path" }
            val acquired = try {
                runInterruptible(ownerDispatcher) {
                    mutex.acquire(options.waitTime.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "ZooKeeper suspend leader lock 획득 실패. path=$path" }
                return null
            }

            if (!acquired) {
                log.debug { "ZooKeeper suspend leader lock 획득 실패 (timeout). path=$path" }
                return null
            }

            log.debug { "ZooKeeper suspend leader lock 획득 성공. path=$path" }

            val acquiredAtNanos = System.nanoTime()
            val delegate = ZooKeeperSuspendLockExtendDelegate(mutex, path)
            val identity = LockIdentity(
                lockName = lockName,
                kind = LockIdentity.AnnotationKind.SINGLE,
                factoryBeanName = ZOOKEEPER_SUSPEND_FACTORY_BEAN_NAME,
            )
            val handle = LeaderLockHandle.real(
                identity = identity,
                token = lockName,
                acquiredAtNanos = acquiredAtNanos,
                extendDelegate = delegate,
            )
            // R16 enforce: ZK 는 TTL 없음 — autoExtend 강제 비활성화 (사용자 설정 무시 + WARN)
            if (options.autoExtend) {
                log.warn {
                    "ZooKeeper 는 TTL 이 없는 세션 기반 락 — autoExtend=true 설정이 무시됩니다. " +
                        "ZK 세션 keepalive 가 lease 역할을 대신합니다. lockName=$lockName"
                }
            }
            val watchdog = LeaderLeaseAutoExtender.start(
                enabled = false,
                leaseTime = options.leaseTime,
                delegate = delegate,
                classifier = ERROR_CLASSIFIER,
            )

            try {
                return withContext(AopScopeAccess.createLockHandleElement(handle)) {
                    action()
                }
            } finally {
                // NonCancellable: 코루틴 취소 시에도 watchdog close + 락 해제가 중단되지 않도록 보호
                withContext(NonCancellable) {
                    try {
                        watchdog.close()
                    } catch (e: Exception) {
                        log.warn(e) { "ZooKeeper suspend watchdog close 실패. path=$path" }
                    }
                    try {
                        runInterruptible(ownerDispatcher) {
                            mutex.release()
                        }
                        log.debug { "ZooKeeper suspend leader lock 반납 완료. path=$path" }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn(e) { "ZooKeeper suspend leader lock 반납 실패. path=$path" }
                    }
                }
            }
        } finally {
            ownerDispatcher.close()
        }
    }
}

/**
 * Runs a suspend leader election action using a ZooKeeper [CuratorFramework].
 */
suspend inline fun <T> CuratorFramework.suspendRunIfLeader(
    lockName: String,
    basePath: String = ZooKeeperSuspendLeaderElector.DEFAULT_BASE_PATH,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? =
    ZooKeeperSuspendLeaderElector(this, basePath, options).runIfLeader(lockName) { action() }
