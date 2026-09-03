package io.bluetape4k.leader.k8s

import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.internal.LeaderFutureBridge
import io.bluetape4k.leader.k8s.internal.KubernetesBackendErrorClassifier
import io.bluetape4k.leader.k8s.internal.KubernetesLeaseLock
import io.bluetape4k.leader.k8s.internal.KubernetesLeaseLockExtendDelegate
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.fabric8.kubernetes.client.KubernetesClient
import java.time.Clock
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * `KubernetesLeaseLeaderElector`는 Kubernetes Lease backend의 lease, ownership 확인, session/TTL 정리를 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property client Kubernetes Lease backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property options Kubernetes Lease backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property clock Kubernetes Lease backend 호출과 상태 계산에 사용하는 속성입니다.
 */
// Single elector는 blocking/async, audit state, lease lifecycle, diagnostics 계약을 함께 구현합니다.
@Suppress("TooManyFunctions")
class KubernetesLeaseLeaderElector @JvmOverloads constructor(
    private val client: KubernetesClient,
    val options: KubernetesLeaseOptions = KubernetesLeaseOptions.Default,
    private val clock: Clock = Clock.systemUTC(),
): LeaderElector,
    LeaderBackendDiagnosticsProvider by KubernetesLeaderBackendDiagnostics,
    io.bluetape4k.leader.LeaderLeaseAcquirerSupport {

    override val leaseAcquirerDelegate: io.bluetape4k.leader.LeaderLeaseAcquirer by lazy {
        io.bluetape4k.leader.internal.LeaderElectorLeaseAdapter({ this }, options.leaderOptions)
    }

    companion object : KLogging() {
        internal const val K8S_FACTORY_BEAN_NAME = "kubernetes-lease-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(KubernetesBackendErrorClassifier)
    }

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
        runWithLock(lockName, null, action)

    override fun <T> runIfLeader(slot: LeaderSlot, action: () -> T): T? =
        runWithLock(slot.lockName, slot.leaderId, action)

    override fun <T> runIfLeaderResult(slot: LeaderSlot, action: () -> T): LeaderRunResult<T> {
        var elected = false
        val value = try {
            runIfLeader(slot) {
                elected = true
                action()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            if (elected) {
                return LeaderRunResult.ActionFailed(e)
            }
            throw e
        }
        return if (elected) LeaderRunResult.Elected(value, leaderId = slot.leaderId) else LeaderRunResult.Skipped
    }

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        runAsyncWithLock(lockName, null, executor, action)

    override fun <T> runAsyncIfLeader(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        runAsyncWithLock(slot.lockName, slot.leaderId, executor, action)

    override fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<LeaderRunResult<T>> {
        var elected = false
        return LeaderFutureBridge.map(runAsyncIfLeader(slot, executor) {
            elected = true
            action()
        }) { value, failure ->
            val cause = failure.unwrapCompletionException()
            when {
                cause is CancellationException -> throw cause
                cause != null && elected -> LeaderRunResult.ActionFailed(cause)
                cause != null -> throw cause
                elected -> LeaderRunResult.Elected(value, leaderId = slot.leaderId)
                else -> LeaderRunResult.Skipped
            }
        }
    }

    override fun state(lockName: String) =
        newLock(lockName, null).state()

    override val supportsAuditLeaderState: Boolean = true

    private fun <T> runWithLock(lockName: String, auditLeaderId: String?, action: () -> T): T? {
        val lock = newLock(lockName, auditLeaderId)
        log.debug { "Kubernetes Lease leadership requested. lockName=$lockName" }
        if (!lock.tryLock(options.leaderOptions.waitTime, options.leaderOptions.leaseTime)) {
            return null
        }

        val acquiredAtNanos = System.nanoTime()
        val delegate = KubernetesLeaseLockExtendDelegate(lock)
        val handle = LeaderLockHandle.real(
            identity = LockIdentity(
                lockName = lockName,
                kind = LockIdentity.AnnotationKind.SINGLE,
                factoryBeanName = K8S_FACTORY_BEAN_NAME,
            ),
            token = lock.ownerToken,
            acquiredAtNanos = acquiredAtNanos,
            extendDelegate = delegate,
            auditLeaderId = auditLeaderId ?: lock.ownerToken,
        )
        val watchdog = LeaderLeaseAutoExtender.start(
            options.leaderOptions.autoExtend,
            options.leaderOptions.leaseTime,
            delegate,
            ERROR_CLASSIFIER,
        )

        try {
            return AopScopeAccess.withPushedSync(handle) { action() }
        } finally {
            watchdog.close()
            runCatching { lock.unlock(options.leaderOptions.minLeaseTime, acquiredAtNanos) }
                .onSuccess { log.debug { "Kubernetes Lease released. lockName=$lockName" } }
                .onFailure { e -> log.warn(e) { "Failed to release Kubernetes Lease. lockName=$lockName" } }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun <T> runAsyncWithLock(
        lockName: String,
        auditLeaderId: String?,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val lock = newLock(lockName, auditLeaderId)

        val lockAcquired = AtomicBoolean()
        val acquiredAtNanosRef = AtomicLong()
        val lifecycleStarted = AtomicBoolean()
        val rejectionCleanupClaimed = AtomicBoolean()
        val releaseIfUnclaimed: () -> Unit = {
            if (lockAcquired.get() && !lifecycleStarted.get() && rejectionCleanupClaimed.compareAndSet(false, true)) {
                release(lock, acquiredAtNanosRef.get(), lockName)
            }
        }
        val acquisitionFuture = CompletableFuture.supplyAsync({
            lock.tryLock(options.leaderOptions.waitTime, options.leaderOptions.leaseTime).also { acquired ->
                if (acquired) {
                    acquiredAtNanosRef.set(System.nanoTime())
                    lockAcquired.set(true)
                }
            }
        }, executor)
        val pipelineFuture: CompletableFuture<T?> = try {
            acquisitionFuture.thenComposeAsync({ acquired ->
                if (!acquired) {
                    CompletableFuture.completedFuture(null)
                } else {
                    lifecycleStarted.set(true)
                    try {
                        runAcquiredAsync(lockName, lock, acquiredAtNanosRef.get(), executor, action)
                    } catch (error: Throwable) {
                        lifecycleStarted.set(false)
                        releaseIfUnclaimed()
                        CompletableFuture.failedFuture(error)
                    }
                }
            }, executor)
        } catch (error: Throwable) {
            acquisitionFuture.whenComplete { acquired, _ ->
                if (acquired == true) releaseIfUnclaimed()
            }
            CompletableFuture.failedFuture(error)
        }
        pipelineFuture.whenComplete { _, failure ->
            if (failure != null) releaseIfUnclaimed()
        }
        return pipelineFuture
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun <T> runAcquiredAsync(
        lockName: String,
        lock: KubernetesLeaseLock,
        acquiredAtNanos: Long,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val delegate = KubernetesLeaseLockExtendDelegate(lock)
        val watchdog = try {
            LeaderLeaseAutoExtender.start(
                options.leaderOptions.autoExtend,
                options.leaderOptions.leaseTime,
                delegate,
                ERROR_CLASSIFIER,
            )
        } catch (e: Throwable) {
            release(lock, acquiredAtNanos, lockName)
            return CompletableFuture.failedFuture(e)
        }
        val actionFuture = try {
            action()
        } catch (e: Throwable) {
            watchdog.close()
            release(lock, acquiredAtNanos, lockName)
            return CompletableFuture.failedFuture(e)
        }

        return actionFuture.handle { value, failure ->
            watchdog.close()
            release(lock, acquiredAtNanos, lockName)
            val cause = failure.unwrapCompletionException()
            if (cause != null) {
                throw cause
            }
            value
        }
    }

    private fun release(
        lock: KubernetesLeaseLock,
        acquiredAtNanos: Long,
        lockName: String,
    ): Boolean =
        try {
            lock.unlock(options.leaderOptions.minLeaseTime, acquiredAtNanos)
        } catch (e: Exception) {
            log.warn(e) { "Failed to release Kubernetes Lease asynchronously. lockName=$lockName" }
            false
        }

    private fun newLock(lockName: String, auditLeaderId: String?): KubernetesLeaseLock {
        val ownerToken = KubernetesLeaseLock.newOwnerToken()
        return KubernetesLeaseLock(
            client = client,
            namespace = options.namespace,
            lockName = lockName,
            ownerToken = ownerToken,
            auditLeaderId = auditLeaderId ?: ownerToken,
            nodeId = options.leaderOptions.nodeId,
            retryDelay = options.retryDelay,
            clock = clock,
        )
    }
}

private fun Throwable?.unwrapCompletionException(): Throwable? =
    if (this is CompletionException && cause != null) cause else this

/**
 * `선언` 호출은 Kubernetes Lease backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lease`, `session`, `TTL`, `owner`, `annotation`, `cleanup` 용어는 backend 계약과 동일하게 유지합니다.
 */
fun <T> KubernetesClient.runIfLeader(
    lockName: String,
    options: KubernetesLeaseOptions = KubernetesLeaseOptions.Default,
    action: () -> T,
): T? = KubernetesLeaseLeaderElector(this, options).runIfLeader(lockName, action)

/**
 * `선언` 호출은 Kubernetes Lease backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lease`, `session`, `TTL`, `owner`, `annotation`, `cleanup` 용어는 backend 계약과 동일하게 유지합니다.
 */
fun <T> KubernetesClient.runAsyncIfLeader(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: KubernetesLeaseOptions = KubernetesLeaseOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    KubernetesLeaseLeaderElector(this, options).runAsyncIfLeader(lockName, executor, action)
