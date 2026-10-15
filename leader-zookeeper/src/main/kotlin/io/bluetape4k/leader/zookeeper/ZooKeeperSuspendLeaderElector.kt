package io.bluetape4k.leader.zookeeper

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.zookeeper.suspendRunIfLeader as currentSuspendRunIfLeader
import io.bluetape4k.leader.zookeeper.internal.ZooKeeperBackendErrorClassifier
import io.bluetape4k.leader.zookeeper.internal.ZooKeeperOwnedInterProcessMutex
import io.bluetape4k.leader.zookeeper.internal.ZooKeeperSuspendLockExtendDelegate
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.apache.curator.framework.CuratorFramework
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `ZooKeeperSuspendLeaderElector`는 ZooKeeper의 suspend 단일 선출을 제공하며 lockName의 lease 획득·연장·해제를 관리합니다.
 *
 * lock 또는 slot을 획득하지 못한 `runIfLeader` 호출은 예외 대신 `null`/skip 결과를 반환합니다.
 * @property client Curator session과 ephemeral lock node를 생성·조회·해제하는 `CuratorFramework`입니다.
 * @property basePath lockName을 붙일 ZooKeeper root path이며 trailing slash는 정규화됩니다. 기본값은 `/leader-election`입니다.
 * @property options lock 대기·lease 정책입니다. ZooKeeper는 TTL을 지원하지 않아 `autoExtend`는 무시되고 session keepalive가 lease 역할을 합니다.
 */
class ZooKeeperSuspendLeaderElector private constructor(
    private val client: CuratorFramework,
    private val basePath: String,
    private val options: LeaderElectionOptions,
): SuspendLeaderElector,
   LeaderBackendDiagnosticsProvider by ZooKeeperLeaderBackendDiagnostics(client),
   AutoCloseable,
   io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirerSupport {

    override val suspendLeaseAcquirerDelegate: io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirer by lazy {
        io.bluetape4k.leader.internal.SuspendLeaderElectorLeaseAdapter({ this }, options)
    }

    private val ownerDispatchers = ZooKeeperOwnerDispatcherPool(
        size = OwnerDispatcherPoolSize,
        threadNamePrefix = "zookeeper-suspend-leader-owner",
    )

    companion object: KLoggingChannel() {
        const val DEFAULT_BASE_PATH = "/leader-election"
        internal const val ZOOKEEPER_SUSPEND_FACTORY_BEAN_NAME = "zookeeper-suspend-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(ZooKeeperBackendErrorClassifier)
        private const val OwnerDispatcherPoolSize = 8
        private val OwnerThreadIds = AtomicInteger()

        @JvmStatic
        operator fun invoke(
            client: CuratorFramework,
            basePath: String = DEFAULT_BASE_PATH,
            options: LeaderElectionOptions = LeaderElectionOptions.Default,
        ): ZooKeeperSuspendLeaderElector =
            ZooKeeperSuspendLeaderElector(client, basePath, options)
    }

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
        ownerDispatchers.withOwner { ownerDispatcher ->
            runWithOwnerDispatcher(lockName, ownerDispatcher, action)
        }

    // 획득 실패, 작업 예외, 취소 정리의 계약을 분리해 보존합니다.
    private suspend fun <T> runWithOwnerDispatcher(
        lockName: String,
        ownerDispatcher: ExecutorCoroutineDispatcher,
        action: suspend () -> T,
    ): T? {
        val path = ZooKeeperPaths.electionPath(basePath, lockName)
        val mutex = ZooKeeperOwnedInterProcessMutex(client, path)

        log.debug { "ZooKeeper suspend leader lock 획득을 요청합니다. path=$path" }
        var acquisitionFailed = false
        val acquired = try {
            runInterruptible(ownerDispatcher) {
                mutex.acquire(options.waitTime.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            acquisitionFailed = true
            log.warn(e) { "ZooKeeper suspend leader lock 획득 실패. path=$path" }
            false
        }

        if (!acquired) {
            if (!acquisitionFailed) {
                log.debug { "ZooKeeper suspend leader lock 획득 실패 (timeout). path=$path" }
            }
            return null
        }

        log.debug { "ZooKeeper suspend leader lock 획득 성공. path=$path" }

        var watchdog: AutoCloseable? = null
        return try {
            val lockPath = runInterruptible(ownerDispatcher) {
                mutex.currentThreadLockPath()
            }
            if (lockPath == null) {
                log.warn { "ZooKeeper suspend leader lock path 조회 실패. path=$path" }
                null
            } else {
                val acquiredAtNanos = System.nanoTime()
                val delegate = ZooKeeperSuspendLockExtendDelegate(client, mutex, path, lockPath)
                val handle = createLockHandle(lockName, acquiredAtNanos, delegate)
                // R16 enforce: ZK 는 TTL 없음 — autoExtend 강제 비활성화 (사용자 설정 무시 + WARN)
                if (options.autoExtend) {
                    log.warn {
                        "ZooKeeper 는 TTL 이 없는 세션 기반 락 — autoExtend=true 설정이 무시됩니다. " +
                                "ZK 세션 keepalive 가 lease 역할을 대신합니다. lockName=$lockName"
                    }
                }
                watchdog = LeaderLeaseAutoExtender.start(
                    enabled = false,
                    leaseTime = options.leaseTime,
                    delegate = delegate,
                    classifier = ERROR_CLASSIFIER,
                )

                withContext(AopScopeAccess.createLockHandleElement(handle)) {
                    action()
                }
            }
        } finally {
            releaseAfterRun(ownerDispatcher, mutex, watchdog, path)
        }
    }

    private fun createLockHandle(
        lockName: String,
        acquiredAtNanos: Long,
        delegate: ZooKeeperSuspendLockExtendDelegate,
    ): LeaderLockHandle {
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.SINGLE,
            factoryBeanName = ZOOKEEPER_SUSPEND_FACTORY_BEAN_NAME,
        )
        return LeaderLockHandle.real(
            identity = identity,
            token = lockName,
            acquiredAtNanos = acquiredAtNanos,
            extendDelegate = delegate,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun releaseAfterRun(
        ownerDispatcher: ExecutorCoroutineDispatcher,
        mutex: ZooKeeperOwnedInterProcessMutex,
        watchdog: AutoCloseable?,
        path: String,
    ) {
        // NonCancellable: 코루틴 취소 시에도 watchdog close + 락 해제가 중단되지 않도록 보호
        withContext(NonCancellable) {
            try {
                watchdog?.let { LeaderLeaseAutoExtender.closeSuspend(it) }
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

    override fun close() {
        ownerDispatchers.close()
    }

    private class ZooKeeperOwnerDispatcherPool(
        size: Int,
        threadNamePrefix: String,
    ): AutoCloseable {
        private val dispatchers: List<ExecutorCoroutineDispatcher> =
            List(size) {
                Executors.newSingleThreadExecutor { task ->
                    Thread(task, "$threadNamePrefix-${OwnerThreadIds.incrementAndGet()}").apply {
                        isDaemon = true
                    }
                }.asCoroutineDispatcher()
            }
        private val available = Channel<ExecutorCoroutineDispatcher>(capacity = size)

        init {
            dispatchers.forEach { dispatcher ->
                available.trySend(dispatcher).getOrThrow()
            }
        }

        suspend fun <T> withOwner(block: suspend (ExecutorCoroutineDispatcher) -> T): T {
            val dispatcher = available.receive()
            return try {
                block(dispatcher)
            } finally {
                available.trySend(dispatcher).getOrThrow()
            }
        }

        override fun close() {
            available.close()
            dispatchers.forEach { it.close() }
        }
    }
}

/** 리팩터링 전 JVM facade의 바이너리 호환성을 보존하는 shim입니다. */
@Deprecated("리팩터링 전 JVM facade 호환성 유지용", level = DeprecationLevel.HIDDEN)
@JvmName("suspendRunIfLeader")
suspend inline fun <T> CuratorFramework.legacySuspendRunIfLeaderByPath(
    path: ZooKeeperElectionPath,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? = this.currentSuspendRunIfLeader(path, options, action)

/** Binary compatibility shim for the pre-refactor JVM facade. */
@Deprecated("Binary compatibility shim", level = DeprecationLevel.HIDDEN)
@JvmName("suspendRunIfLeader")
suspend inline fun <T> CuratorFramework.legacySuspendRunIfLeaderByName(
    lockName: String,
    basePath: String = ZooKeeperSuspendLeaderElector.DEFAULT_BASE_PATH,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? = this.currentSuspendRunIfLeader(lockName, basePath, options, action)
