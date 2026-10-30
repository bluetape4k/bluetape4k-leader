package io.bluetape4k.leader.zookeeper

import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLeaseAcquirer
import io.bluetape4k.leader.LeaderLeaseAcquirerSupport
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.internal.LeaderElectorLeaseAdapter
import io.bluetape4k.leader.internal.LeaderFutureBridge
import io.bluetape4k.leader.zookeeper.internal.ZooKeeperBackendErrorClassifier
import io.bluetape4k.leader.zookeeper.internal.ZooKeeperLockExtendDelegate
import io.bluetape4k.leader.zookeeper.internal.ZooKeeperOwnedInterProcessMutex
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import org.apache.curator.framework.CuratorFramework
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import io.bluetape4k.leader.zookeeper.runAsyncIfLeader as currentRunAsyncIfLeader
import io.bluetape4k.leader.zookeeper.runIfLeader as currentRunIfLeader

/**
 * `ZooKeeperLeaderElector`는 ZooKeeper의 blocking 단일 선출을 제공하며 lockName의 lease 획득·연장·해제를 관리합니다.
 *
 * lock 또는 slot을 획득하지 못한 `runIfLeader` 호출은 예외 대신 `null`/skip 결과를 반환합니다.
 * @property client Curator session과 ephemeral lock node를 생성·조회·해제하는 `CuratorFramework`입니다.
 * @property basePath lockName을 붙일 ZooKeeper root path이며 trailing slash는 정규화됩니다. 기본값은 `/leader-election`입니다.
 * @property options lock 대기·lease 정책입니다. ZooKeeper는 TTL을 지원하지 않아 `autoExtend`는 무시되고 session keepalive가 lease 역할을 합니다.
 */
class ZooKeeperLeaderElector private constructor(
    private val client: CuratorFramework,
    private val basePath: String,
    private val options: LeaderElectionOptions,
): LeaderElector,
   LeaderBackendDiagnosticsProvider by ZooKeeperLeaderBackendDiagnostics(client),
   LeaderLeaseAcquirerSupport {

    override val leaseAcquirerDelegate: LeaderLeaseAcquirer by lazy {
        LeaderElectorLeaseAdapter({ this }, options)
    }

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

    // Cleanup must rethrow an interrupted release while restoring the flag, even from finally.
    @Suppress("ThrowingExceptionFromFinally", "LongMethod", "ReturnCount")
    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        val path = ZooKeeperPaths.electionPath(basePath, lockName)
        val mutex = ZooKeeperOwnedInterProcessMutex(client, path)

        log.debug { "ZooKeeper leader lock 획득을 요청합니다. path=$path" }

        val acquired = try {
            mutex.acquire(options.waitTime.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn(e) { "ZooKeeper leader lock 획득 중 interrupt. path=$path" }
            throw e
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeper leader lock 획득 실패. path=$path" }
            return null
        }

        if (!acquired) {
            log.debug { "ZooKeeper leader lock 획득 실패 (timeout). path=$path" }
            return null
        }

        log.debug { "ZooKeeper leader lock 획득 성공. path=$path" }

        val lockPath = mutex.currentThreadLockPath()
        if (lockPath == null) {
            log.warn { "ZooKeeper leader lock path 조회 실패. path=$path" }
            try {
                mutex.release()
            } catch (e: Exception) {
                log.warn(e) { "ZooKeeper leader lock path 조회 실패 후 반납 실패. path=$path" }
            }
            return null
        }

        val acquiredAtNanos = System.nanoTime()
        val delegate = ZooKeeperLockExtendDelegate(client, mutex, path, lockPath)
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
                throw e
            } catch (e: Exception) {
                log.warn(e) { "ZooKeeper leader lock 반납 실패. path=$path" }
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
}

/** 리팩터링 전 JVM facade의 바이너리 호환성을 보존하는 shim입니다. */
@Deprecated("리팩터링 전 JVM facade 호환성 유지용", level = DeprecationLevel.HIDDEN)
@JvmName("runIfLeader")
inline fun <T> CuratorFramework.legacyRunIfLeaderByPath(
    path: ZooKeeperElectionPath,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> T,
): T? = this.currentRunIfLeader(path, options, action)

/** Binary compatibility shim for the pre-refactor JVM facade. */
@Deprecated("Binary compatibility shim", level = DeprecationLevel.HIDDEN)
@JvmName("runIfLeader")
inline fun <T> CuratorFramework.legacyRunIfLeaderByName(
    lockName: String,
    basePath: String = ZooKeeperLeaderElector.DEFAULT_BASE_PATH,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> T,
): T? = this.currentRunIfLeader(lockName, basePath, options, action)

/** Binary compatibility shim for the pre-refactor JVM facade. */
@Deprecated("Binary compatibility shim", level = DeprecationLevel.HIDDEN)
@JvmName("runAsyncIfLeader")
fun <T> CuratorFramework.legacyRunAsyncIfLeaderByPath(
    path: ZooKeeperElectionPath,
    executor: Executor = VirtualThreadExecutor,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> = this.currentRunAsyncIfLeader(path, executor, options, action)

/** Binary compatibility shim for the pre-refactor JVM facade. */
@Deprecated("Binary compatibility shim", level = DeprecationLevel.HIDDEN)
@JvmName("runAsyncIfLeader")
fun <T> CuratorFramework.legacyRunAsyncIfLeaderByName(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    basePath: String = ZooKeeperLeaderElector.DEFAULT_BASE_PATH,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> = this.currentRunAsyncIfLeader(lockName, executor, basePath, options, action)
