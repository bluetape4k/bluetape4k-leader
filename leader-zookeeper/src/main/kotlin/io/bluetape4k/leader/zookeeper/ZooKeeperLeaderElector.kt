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
 * Apache Curator [InterProcessMutex] 기반 ZooKeeper 단일 리더 선출 구현체입니다.
 *
 * ## 동작/계약
 * - [lockName]별 ZooKeeper 경로에 [InterProcessMutex]를 생성하고 [LeaderElectionOptions.waitTime] 동안 획득을 시도합니다.
 * - 획득에 실패하면 [action]을 실행하지 않고 `null`을 반환합니다.
 * - 획득에 성공하면 [action]을 실행하고 `finally`에서 반드시 `release()`를 호출합니다.
 * - Curator recipe 특성상 세션이 끊기면 ZooKeeper ephemeral node가 사라져 락이 자동 해제됩니다.
 *
 * ## ExtendDelegate 통합 (T13 PR 8 / Issue #79)
 *
 * - acquire 후 [ZooKeeperLockExtendDelegate] 를 생성하여 [LeaderLockHandle.Real] 와 동일 reference 공유 (AC-15).
 * - aspect 가 `LockExtender.extendActiveLock` 호출 시 동일 delegate 를 통해 passthrough extend 반환 — Spec §6 row 12.
 *
 * ## R16 enforce — ZooKeeper 는 TTL 없음
 *
 * ZooKeeper 는 세션 기반 락 (ephemeral znode) — TTL 개념이 없습니다.
 * 따라서 [LeaderLeaseAutoExtender.start] 는 **항상 `enabled=false`** 호출 강제 (사용자가 `options.autoExtend=true`
 * 설정해도 무시 + WARN 로그). lease 갱신은 ZK 세션 keepalive 가 담당.
 *
 * ```kotlin
 * val elector = ZooKeeperLeaderElector(curator)
 * val result = elector.runIfLeader("daily-job") { runJob() }
 * ```
 *
 * @param client 시작된 [CuratorFramework] 클라이언트. 수명 관리는 호출자 책임입니다.
 * @param basePath 리더 선출 znodes가 생성될 기준 경로
 * @param options 리더 선출 옵션
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
 * ZooKeeper [CuratorFramework]로 리더 선출 작업을 실행합니다.
 */
inline fun <T> CuratorFramework.runIfLeader(
    lockName: String,
    basePath: String = ZooKeeperLeaderElector.DEFAULT_BASE_PATH,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> T,
): T? =
    ZooKeeperLeaderElector(this, basePath, options).runIfLeader(lockName) { action() }

/**
 * ZooKeeper [CuratorFramework]로 비동기 리더 선출 작업을 실행합니다.
 */
fun <T> CuratorFramework.runAsyncIfLeader(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    basePath: String = ZooKeeperLeaderElector.DEFAULT_BASE_PATH,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    ZooKeeperLeaderElector(this, basePath, options).runAsyncIfLeader(lockName, executor, action)
