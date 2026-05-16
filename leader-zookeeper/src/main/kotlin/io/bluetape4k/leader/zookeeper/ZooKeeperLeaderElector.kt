package io.bluetape4k.leader.zookeeper

import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.zookeeper.internal.ZooKeeperBackendErrorClassifier
import io.bluetape4k.leader.zookeeper.internal.ZooKeeperLockExtendDelegate
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.locks.InterProcessMutex
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * ZooKeeper single-leader election implementation based on Apache Curator [InterProcessMutex].
 *
 * ## Behavior / Contract
 * - Creates an [InterProcessMutex] at the ZooKeeper path for each [lockName] and attempts acquisition
 *   for up to [LeaderElectionOptions.waitTime].
 * - Returns `null` without executing [action] if acquisition fails.
 * - Executes [action] on successful acquisition and always calls `release()` in a `finally` block.
 * - Due to Curator recipe semantics, when the session disconnects the ZooKeeper ephemeral node disappears
 *   and the lock is released automatically.
 *
 * ## ExtendDelegate Integration (T13 PR 8 / Issue #79)
 *
 * - After acquire, creates [ZooKeeperLockExtendDelegate] sharing the same reference as [LeaderLockHandle.Real] (AC-15).
 * - When the aspect calls `LockExtender.extendActiveLock`, returns a passthrough extend via the same delegate — Spec §6 row 12.
 *
 * ## R16 Enforcement — ZooKeeper Has No TTL
 *
 * ZooKeeper uses session-based locks (ephemeral znodes) — there is no TTL concept.
 * Therefore [LeaderLeaseAutoExtender.start] is **always called with `enabled=false`** (ignored even if the user
 * sets `options.autoExtend=true`, with a WARN log). Lease renewal is handled by ZK session keepalive.
 *
 * ```kotlin
 * val elector = ZooKeeperLeaderElector(curator)
 * val result = elector.runIfLeader("daily-job") { runJob() }
 * ```
 *
 * @param client A started [CuratorFramework] client. Lifecycle management is the caller's responsibility.
 * @param basePath Base path under which leader election znodes are created
 * @param options Leader election options
 */
class ZooKeeperLeaderElector private constructor(
    private val client: CuratorFramework,
    private val basePath: String,
    private val options: LeaderElectionOptions,
): LeaderElector {

    companion object: KLogging() {
        const val DEFAULT_BASE_PATH = "/leader-election"
        internal const val ZOOKEEPER_FACTORY_BEAN_NAME = "zookeeper-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(ZooKeeperBackendErrorClassifier)

        @JvmStatic
        operator fun invoke(
            client: CuratorFramework,
            basePath: String = DEFAULT_BASE_PATH,
            options: LeaderElectionOptions = LeaderElectionOptions.Default,
        ): ZooKeeperLeaderElector =
            ZooKeeperLeaderElector(client, basePath, options)
    }

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        val path = ZooKeeperPaths.electionPath(basePath, lockName)
        val mutex = InterProcessMutex(client, path)

        log.debug { "ZooKeeper leader lock 획득을 요청합니다. path=$path" }
        val acquired = try {
            mutex.acquire(options.waitTime.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn(e) { "ZooKeeper leader lock 획득 중 interrupt. path=$path" }
            return null
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeper leader lock 획득 실패. path=$path" }
            return null
        }

        if (!acquired) {
            log.debug { "ZooKeeper leader lock 획득 실패 (timeout). path=$path" }
            return null
        }

        log.debug { "ZooKeeper leader lock 획득 성공. path=$path" }

        val acquiredAtNanos = System.nanoTime()
        val delegate = ZooKeeperLockExtendDelegate(mutex, path)
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.SINGLE,
            factoryBeanName = ZOOKEEPER_FACTORY_BEAN_NAME,
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

        return try {
            AopScopeAccess.withPushedSync(handle) { action() }
        } finally {
            try {
                watchdog.close()
            } catch (e: Exception) {
                log.warn(e) { "ZooKeeper watchdog close 실패. path=$path" }
            }
            try {
                mutex.release()
                log.debug { "ZooKeeper leader lock 반납 완료. path=$path" }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                log.warn(e) { "ZooKeeper leader lock 반납 중 interrupt. path=$path" }
            } catch (e: Exception) {
                log.warn(e) { "ZooKeeper leader lock 반납 실패. path=$path" }
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
}

/**
 * Runs a leader-elected action using the ZooKeeper [CuratorFramework].
 */
inline fun <T> CuratorFramework.runIfLeader(
    lockName: String,
    basePath: String = ZooKeeperLeaderElector.DEFAULT_BASE_PATH,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> T,
): T? =
    ZooKeeperLeaderElector(this, basePath, options).runIfLeader(lockName) { action() }

/**
 * Runs an async leader-elected action using the ZooKeeper [CuratorFramework].
 */
fun <T> CuratorFramework.runAsyncIfLeader(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    basePath: String = ZooKeeperLeaderElector.DEFAULT_BASE_PATH,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    ZooKeeperLeaderElector(this, basePath, options).runAsyncIfLeader(lockName, executor, action)
