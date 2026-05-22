package io.bluetape4k.leader.k8s

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.k8s.internal.KubernetesLeaseLock
import io.bluetape4k.leader.k8s.internal.KubernetesLeaseLockExtendDelegate
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.fabric8.kubernetes.client.KubernetesClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Clock

/**
 * Coroutine leader election backed by Kubernetes `coordination.k8s.io/v1` Lease objects.
 *
 * ## Behavior / Contract
 * - Fabric8 client calls are wrapped in [Dispatchers.IO].
 * - Cancellation is rethrown after owner-conditional release in a [NonCancellable] cleanup section.
 * - The supplied [KubernetesClient] is caller-owned and is never closed by this elector.
 *
 * ```kotlin
 * val election = KubernetesLeaseSuspendLeaderElector(client, KubernetesLeaseOptions(namespace = "operators"))
 * val result = election.runIfLeader("nightly-sync") {
 *     syncData()
 * }
 * ```
 */
class KubernetesLeaseSuspendLeaderElector @JvmOverloads constructor(
    private val client: KubernetesClient,
    val options: KubernetesLeaseOptions = KubernetesLeaseOptions.Default,
    private val clock: Clock = Clock.systemUTC(),
) : SuspendLeaderElector {

    companion object : KLoggingChannel() {
        internal const val K8S_SUSPEND_FACTORY_BEAN_NAME = "kubernetes-lease-suspend-leader-elector"
    }

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
        runWithLock(lockName, null, action)

    override suspend fun <T> runIfLeader(slot: LeaderSlot, action: suspend () -> T): T? =
        runWithLock(slot.lockName, slot.leaderId, action)

    override suspend fun <T> runIfLeaderResultSuspend(
        slot: LeaderSlot,
        action: suspend () -> T,
    ): LeaderRunResult<T> {
        var elected = false
        val value = try {
            runIfLeader(slot) {
                elected = true
                action()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (elected) {
                return LeaderRunResult.ActionFailed(e)
            }
            throw e
        }
        return if (elected) LeaderRunResult.Elected(value, leaderId = slot.leaderId) else LeaderRunResult.Skipped
    }

    override fun state(lockName: String) =
        newLock(lockName, null).state()

    private suspend fun <T> runWithLock(
        lockName: String,
        auditLeaderId: String?,
        action: suspend () -> T,
    ): T? {
        currentCoroutineContext().ensureActive()
        val lock = newLock(lockName, auditLeaderId)
        log.debug { "Kubernetes Lease leadership requested (suspend). lockName=$lockName" }
        val acquired = withContext(Dispatchers.IO) {
            lock.tryLock(options.leaderOptions.waitTime, options.leaderOptions.leaseTime)
        }
        if (!acquired) {
            return null
        }

        val acquiredAtNanos = System.nanoTime()
        val delegate = KubernetesLeaseLockExtendDelegate(lock)
        val handle = LeaderLockHandle.real(
            identity = LockIdentity(
                lockName = lockName,
                kind = LockIdentity.AnnotationKind.SINGLE,
                factoryBeanName = K8S_SUSPEND_FACTORY_BEAN_NAME,
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
            KubernetesLeaseLeaderElector.ERROR_CLASSIFIER,
        )

        try {
            return withContext(AopScopeAccess.createLockHandleElement(handle)) {
                action()
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                watchdog.close()
                try {
                    lock.unlock(options.leaderOptions.minLeaseTime, acquiredAtNanos)
                    log.debug { "Kubernetes Lease released (suspend). lockName=$lockName" }
                } catch (e: Exception) {
                    // Inside NonCancellable, CancellationException from the backend is a backend error,
                    // not coroutine cancellation. Log and swallow to complete cleanup.
                    log.warn(e) { "Failed to release Kubernetes Lease (suspend). lockName=$lockName" }
                }
            }
        }
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

/**
 * Runs [action] only when this client acquires the Kubernetes Lease in a coroutine.
 */
suspend fun <T> KubernetesClient.suspendRunIfLeader(
    lockName: String,
    options: KubernetesLeaseOptions = KubernetesLeaseOptions.Default,
    action: suspend () -> T,
): T? = KubernetesLeaseSuspendLeaderElector(this, options).runIfLeader(lockName, action)
