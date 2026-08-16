package io.bluetape4k.leader.zookeeper

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
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
 * `ZooKeeperSuspendLeaderElector`는 ZooKeeper backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property client ZooKeeper backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property basePath ZooKeeper backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property options ZooKeeper backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class ZooKeeperSuspendLeaderElector private constructor(
    private val client: CuratorFramework,
    private val basePath: String,
    private val options: LeaderElectionOptions,
): SuspendLeaderElector,
    LeaderBackendDiagnosticsProvider by ZooKeeperLeaderBackendDiagnostics(client),
    AutoCloseable {

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

    private suspend fun <T> runWithOwnerDispatcher(
        lockName: String,
        ownerDispatcher: ExecutorCoroutineDispatcher,
        action: suspend () -> T,
    ): T? {
        val path = ZooKeeperPaths.electionPath(basePath, lockName)
        val mutex = ZooKeeperOwnedInterProcessMutex(client, path)

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

        var watchdog: AutoCloseable? = null
        try {
            val lockPath = runInterruptible(ownerDispatcher) {
                mutex.currentThreadLockPath()
            }
            if (lockPath == null) {
                log.warn { "ZooKeeper suspend leader lock path 조회 실패. path=$path" }
                return null
            }

            val acquiredAtNanos = System.nanoTime()
            val delegate = ZooKeeperSuspendLockExtendDelegate(client, mutex, path, lockPath)
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
            watchdog = LeaderLeaseAutoExtender.start(
                enabled = false,
                leaseTime = options.leaseTime,
                delegate = delegate,
                classifier = ERROR_CLASSIFIER,
            )

            return withContext(AopScopeAccess.createLockHandleElement(handle)) {
                action()
            }
        } finally {
            // NonCancellable: 코루틴 취소 시에도 watchdog close + 락 해제가 중단되지 않도록 보호
            withContext(NonCancellable) {
                try {
                    watchdog?.close()
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

/**
 * `선언` 호출은 ZooKeeper backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
suspend inline fun <T> CuratorFramework.suspendRunIfLeader(
    path: ZooKeeperElectionPath,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? {
    val elector = ZooKeeperSuspendLeaderElector(this, path.basePath, options)
    return try {
        elector.runIfLeader(path.lockName) { action() }
    } finally {
        elector.close()
    }
}

/**
 * `선언` 호출은 ZooKeeper backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
suspend inline fun <T> CuratorFramework.suspendRunIfLeader(
    lockName: String,
    basePath: String = ZooKeeperSuspendLeaderElector.DEFAULT_BASE_PATH,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? =
    suspendRunIfLeader(ZooKeeperElectionPath(lockName, basePath), options, action)
