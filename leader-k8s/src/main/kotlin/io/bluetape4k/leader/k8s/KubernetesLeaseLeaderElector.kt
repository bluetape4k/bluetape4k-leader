package io.bluetape4k.leader.k8s

import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
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

/**
 * Blocking and async leader election backed by Kubernetes `coordination.k8s.io/v1` Lease objects.
 *
 * ## Behavior / Contract
 * - Contention returns `null` instead of throwing.
 * - Action exceptions are propagated to the caller.
 * - Lease release and extension are owner-conditional using a per-acquisition fencing token.
 * - The supplied [KubernetesClient] is caller-owned and is never closed by this elector.
 *
 * ```kotlin
 * val election = KubernetesLeaseLeaderElector(client, KubernetesLeaseOptions(namespace = "operators"))
 * val result = election.runIfLeader("daily-report") {
 *     generateReport()
 * }
 * ```
 */
class KubernetesLeaseLeaderElector @JvmOverloads constructor(
    private val client: KubernetesClient,
    val options: KubernetesLeaseOptions = KubernetesLeaseOptions.Default,
    private val clock: Clock = Clock.systemUTC(),
) : LeaderElector {

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
        return runAsyncIfLeader(slot, executor) {
            elected = true
            action()
        }.handle { value, failure ->
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

    private fun <T> runAsyncWithLock(
        lockName: String,
        auditLeaderId: String?,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val lock = newLock(lockName, auditLeaderId)

        return CompletableFuture.supplyAsync({
            lock.tryLock(options.leaderOptions.waitTime, options.leaderOptions.leaseTime)
        }, executor).thenComposeAsync({ acquired ->
            if (!acquired) {
                CompletableFuture.completedFuture(null)
            } else {
                runAcquiredAsync(lockName, lock, executor, action)
            }
        }, executor)
    }

    private fun <T> runAcquiredAsync(
        lockName: String,
        lock: KubernetesLeaseLock,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val acquiredAtNanos = System.nanoTime()
        val delegate = KubernetesLeaseLockExtendDelegate(lock)
        val watchdog = LeaderLeaseAutoExtender.start(
            options.leaderOptions.autoExtend,
            options.leaderOptions.leaseTime,
            delegate,
            ERROR_CLASSIFIER,
        )
        val actionFuture = try {
            action()
        } catch (e: Exception) {
            watchdog.close()
            release(lock, acquiredAtNanos, lockName)
            return CompletableFuture.failedFuture(e)
        }

        return actionFuture.handleAsync({ value, failure ->
            watchdog.close()
            release(lock, acquiredAtNanos, lockName)
            val cause = failure.unwrapCompletionException()
            if (cause != null) {
                throw cause
            }
            value
        }, executor)
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
 * Runs [action] only when this client acquires the Kubernetes Lease.
 */
fun <T> KubernetesClient.runIfLeader(
    lockName: String,
    options: KubernetesLeaseOptions = KubernetesLeaseOptions.Default,
    action: () -> T,
): T? = KubernetesLeaseLeaderElector(this, options).runIfLeader(lockName, action)

/**
 * Runs [action] asynchronously only when this client acquires the Kubernetes Lease.
 */
fun <T> KubernetesClient.runAsyncIfLeader(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: KubernetesLeaseOptions = KubernetesLeaseOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    KubernetesLeaseLeaderElector(this, options).runAsyncIfLeader(lockName, executor, action)
