package io.bluetape4k.leader.k8s

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.k8s.internal.KubernetesLeaseLock
import io.bluetape4k.leader.k8s.internal.KubernetesLeaseLockExtendDelegate
import io.bluetape4k.leader.k8s.internal.KubernetesLeaseGroupAcquisitionDeadline
import io.bluetape4k.leader.k8s.internal.KubernetesLeaseNames
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
import java.util.concurrent.ThreadLocalRandom
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Coroutine group leader election backed by one Kubernetes Lease per group slot.
 *
 * ## Behavior / Contract
 * - Fabric8 client calls are wrapped in [Dispatchers.IO].
 * - Cancellation is rethrown after owner-conditional slot release in a [NonCancellable] cleanup section.
 * - The supplied [KubernetesClient] is caller-owned and is never closed by this elector.
 *
 * ```kotlin
 * val election = KubernetesLeaseSuspendLeaderGroupElector(client)
 * election.runIfLeader("partition-worker") {
 *     processPartitionSuspend()
 * }
 * ```
 */
class KubernetesLeaseSuspendLeaderGroupElector @JvmOverloads constructor(
    private val client: KubernetesClient,
    val options: KubernetesLeaseGroupOptions = KubernetesLeaseGroupOptions.Default,
    private val clock: Clock = Clock.systemUTC(),
) : SuspendLeaderGroupElector {

    companion object : KLoggingChannel() {
        internal const val K8S_SUSPEND_GROUP_FACTORY_BEAN_NAME = "kubernetes-lease-suspend-leader-group-elector"
    }

    override val maxLeaders: Int = options.maxLeaders

    override fun activeCount(lockName: String): Int =
        state(lockName).activeCount

    override fun availableSlots(lockName: String): Int =
        state(lockName).availableSlots

    override fun state(lockName: String): LeaderGroupState {
        val leaders = currentLeaders(lockName)
        return LeaderGroupState(lockName, maxLeaders, leaders.size, leaders)
    }

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
        runWithGroupSlot(lockName, null, action)

    override suspend fun <T> runIfLeader(slot: LeaderSlot, action: suspend () -> T): T? =
        runWithGroupSlot(slot.lockName, slot.leaderId, action)

    override suspend fun <T> runIfLeaderResultSuspend(
        slot: LeaderSlot,
        action: suspend () -> T,
    ): LeaderRunResult<T> {
        var elected = false
        val value = try {
            runWithGroupSlot(slot.lockName, slot.leaderId) {
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

    private suspend fun <T> runWithGroupSlot(
        lockName: String,
        auditLeaderId: String?,
        action: suspend () -> T,
    ): T? {
        currentCoroutineContext().ensureActive()
        val acquired = withContext(Dispatchers.IO) {
            acquire(lockName, auditLeaderId)
        } ?: return null

        val lock = acquired.lock
        val delegate = KubernetesLeaseLockExtendDelegate(lock)
        val handle = handle(lockName, lock, acquired.slot, acquired.acquiredAtNanos, delegate, auditLeaderId)
        val watchdog = LeaderLeaseAutoExtender.start(
            enabled = false,
            leaseTime = options.leaderGroupOptions.leaseTime,
            delegate = delegate,
            classifier = KubernetesLeaseLeaderElector.ERROR_CLASSIFIER,
        )

        try {
            return withContext(AopScopeAccess.createLockHandleElement(handle)) {
                AopScopeAccess.setCapture(handle)
                try {
                    action()
                } finally {
                    AopScopeAccess.clearCapture()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                watchdog.close()
                try {
                    lock.unlock(options.leaderGroupOptions.minLeaseTime, acquired.acquiredAtNanos)
                    log.debug { "Kubernetes Lease group slot released (suspend). lockName=$lockName, slot=${acquired.slot}" }
                } catch (e: Exception) {
                    log.warn(e) {
                        "Failed to release Kubernetes Lease group slot (suspend). lockName=$lockName, slot=${acquired.slot}"
                    }
                }
            }
        }
    }

    private fun acquire(lockName: String, auditLeaderId: String?): AcquiredSlot? {
        val deadline = KubernetesLeaseGroupAcquisitionDeadline.fromNow(options.leaderGroupOptions.waitTime)
        val startSlot = ThreadLocalRandom.current().nextInt(maxLeaders)

        for (attempt in 0 until maxLeaders) {
            val slot = (startSlot + attempt) % maxLeaders
            val remaining = deadline.remaining()
            if (attempt > 0 && remaining <= Duration.ZERO) {
                break
            }
            val lock = newSlotLock(lockName, slot, auditLeaderId)
            if (lock.tryLock(slotBudget(remaining, maxLeaders - attempt), options.leaderGroupOptions.leaseTime)) {
                log.debug { "Kubernetes Lease group slot acquired (suspend). lockName=$lockName, slot=$slot" }
                return AcquiredSlot(slot, lock, System.nanoTime())
            }
        }

        log.debug { "Kubernetes Lease group acquisition skipped (suspend). lockName=$lockName" }
        return null
    }

    private fun currentLeaders(lockName: String): List<LeaderLease> =
        (0 until maxLeaders).mapNotNull { slot ->
            runCatching {
                newSlotLock(lockName, slot, null)
                    .state()
                    .leader
                    ?.copy(slot = slot)
            }.getOrElse { e ->
                log.warn(e) { "Kubernetes Lease group state query failed (suspend). lockName=$lockName, slot=$slot" }
                null
            }
        }

    private fun handle(
        lockName: String,
        lock: KubernetesLeaseLock,
        slot: Int,
        acquiredAtNanos: Long,
        delegate: KubernetesLeaseLockExtendDelegate,
        auditLeaderId: String?,
    ): LeaderLockHandle.Real =
        LeaderLockHandle.real(
            identity = LockIdentity(
                lockName = lockName,
                kind = LockIdentity.AnnotationKind.GROUP,
                factoryBeanName = K8S_SUSPEND_GROUP_FACTORY_BEAN_NAME,
                groupParams = LockIdentity.GroupParams(maxLeaders),
            ),
            token = lock.ownerToken,
            acquiredAtNanos = acquiredAtNanos,
            slotId = slot.toString(),
            extendDelegate = delegate,
            auditLeaderId = auditLeaderId ?: lock.ownerToken,
        )

    private fun newSlotLock(lockName: String, slot: Int, auditLeaderId: String?): KubernetesLeaseLock {
        val ownerToken = KubernetesLeaseLock.newOwnerToken()
        return KubernetesLeaseLock(
            client = client,
            namespace = options.namespace,
            lockName = KubernetesLeaseNames.groupSlotLeaseName(lockName, slot, maxLeaders),
            ownerToken = ownerToken,
            auditLeaderId = auditLeaderId ?: ownerToken,
            nodeId = options.leaderGroupOptions.nodeId,
            retryDelay = options.retryDelay,
            clock = clock,
        )
    }

    private fun slotBudget(remaining: Duration, remainingSlots: Int): Duration {
        if (remaining <= Duration.ZERO) {
            return Duration.ZERO
        }
        return (remaining.inWholeMilliseconds / remainingSlots)
            .coerceAtLeast(1L)
            .milliseconds
    }

    private data class AcquiredSlot(
        val slot: Int,
        val lock: KubernetesLeaseLock,
        val acquiredAtNanos: Long,
    )
}

/**
 * Runs [action] only when this client acquires one Kubernetes Lease group slot in a coroutine.
 */
suspend fun <T> KubernetesClient.suspendRunIfLeaderGroup(
    lockName: String,
    options: KubernetesLeaseGroupOptions = KubernetesLeaseGroupOptions.Default,
    action: suspend () -> T,
): T? =
    KubernetesLeaseSuspendLeaderGroupElector(this, options).runIfLeader(lockName) { action() }
