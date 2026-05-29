package io.bluetape4k.leader.k8s

import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.k8s.internal.KubernetesLeaseLock
import io.bluetape4k.leader.k8s.internal.KubernetesLeaseLockExtendDelegate
import io.bluetape4k.leader.k8s.internal.KubernetesLeaseGroupAcquisitionDeadline
import io.bluetape4k.leader.k8s.internal.KubernetesLeaseNames
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.fabric8.kubernetes.client.KubernetesClient
import java.time.Clock
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.ThreadLocalRandom
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Blocking and async group leader election backed by one Kubernetes Lease per group slot.
 *
 * ## Behavior / Contract
 * - At most [maxLeaders] actions run concurrently for a logical `lockName`.
 * - Each slot is represented as a separate Kubernetes Lease named `<lockName>-slot-<index>`.
 * - Release and extension are owner-conditional for the acquired slot Lease.
 * - The supplied [KubernetesClient] is caller-owned and is never closed by this elector.
 *
 * ```kotlin
 * val election = KubernetesLeaseLeaderGroupElector(
 *     client,
 *     KubernetesLeaseGroupOptions(leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 3)),
 * )
 * val result = election.runIfLeader("partition-worker") {
 *     processPartition()
 * }
 * ```
 */
class KubernetesLeaseLeaderGroupElector @JvmOverloads constructor(
    private val client: KubernetesClient,
    val options: KubernetesLeaseGroupOptions = KubernetesLeaseGroupOptions.Default,
    private val clock: Clock = Clock.systemUTC(),
) : LeaderGroupElector {

    companion object : KLogging() {
        internal const val K8S_GROUP_FACTORY_BEAN_NAME = "kubernetes-lease-leader-group-elector"
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

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
        runWithGroupSlot(lockName, null, action)

    override fun <T> runIfLeader(slot: LeaderSlot, action: () -> T): T? =
        runWithGroupSlot(slot.lockName, slot.leaderId, action)

    override fun <T> runIfLeaderResult(slot: LeaderSlot, action: () -> T): LeaderRunResult<T> {
        var elected = false
        val value = try {
            runWithGroupSlot(slot.lockName, slot.leaderId) {
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
        runAsyncWithGroupSlot(lockName, null, executor, action)

    override fun <T> runAsyncIfLeader(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        runAsyncWithGroupSlot(slot.lockName, slot.leaderId, executor, action)

    override fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<LeaderRunResult<T>> {
        var elected = false
        return runAsyncWithGroupSlot(slot.lockName, slot.leaderId, executor) {
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

    private fun <T> runWithGroupSlot(lockName: String, auditLeaderId: String?, action: () -> T): T? {
        val acquired = acquire(lockName, auditLeaderId) ?: return null
        val lock = acquired.lock
        val delegate = KubernetesLeaseLockExtendDelegate(lock)
        val handle = handle(lockName, lock, acquired.slot, acquired.acquiredAtNanos, delegate, auditLeaderId)
        val watchdog = LeaderLeaseAutoExtender.start(
            enabled = false,
            leaseTime = options.leaderGroupOptions.leaseTime,
            delegate = delegate,
            classifier = KubernetesLeaseLeaderElector.ERROR_CLASSIFIER,
        )

        return try {
            AopScopeAccess.withPushedSync(handle) {
                AopScopeAccess.setCapture(handle)
                try {
                    action()
                } finally {
                    AopScopeAccess.clearCapture()
                }
            }
        } finally {
            watchdog.close()
            release(lock, acquired.acquiredAtNanos, lockName, acquired.slot)
        }
    }

    private fun <T> runAsyncWithGroupSlot(
        lockName: String,
        auditLeaderId: String?,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        CompletableFuture.supplyAsync({ acquire(lockName, auditLeaderId) }, executor)
            .thenComposeAsync({ acquired ->
                if (acquired == null) {
                    CompletableFuture.completedFuture(null)
                } else {
                    runAcquiredAsync(lockName, acquired, auditLeaderId, executor, action)
                }
            }, executor)

    private fun <T> runAcquiredAsync(
        lockName: String,
        acquired: AcquiredSlot,
        auditLeaderId: String?,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val lock = acquired.lock
        val delegate = KubernetesLeaseLockExtendDelegate(lock)
        val watchdog = LeaderLeaseAutoExtender.start(
            enabled = false,
            leaseTime = options.leaderGroupOptions.leaseTime,
            delegate = delegate,
            classifier = KubernetesLeaseLeaderElector.ERROR_CLASSIFIER,
        )
        val actionFuture = try {
            val handle = handle(lockName, lock, acquired.slot, acquired.acquiredAtNanos, delegate, auditLeaderId)
            AopScopeAccess.withPushedSync(handle) { action() }
        } catch (e: Exception) {
            watchdog.close()
            release(lock, acquired.acquiredAtNanos, lockName, acquired.slot)
            return CompletableFuture.failedFuture(e)
        }

        return actionFuture.handleAsync({ value, failure ->
            watchdog.close()
            release(lock, acquired.acquiredAtNanos, lockName, acquired.slot)
            val cause = failure.unwrapCompletionException()
            if (cause != null) {
                throw cause
            }
            value
        }, executor)
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
            val slotBudget = slotBudget(remaining, maxLeaders - attempt)
            val lock = newSlotLock(lockName, slot, auditLeaderId)
            if (lock.tryLock(slotBudget, options.leaderGroupOptions.leaseTime)) {
                log.debug { "Kubernetes Lease group slot acquired. lockName=$lockName, slot=$slot" }
                return AcquiredSlot(slot, lock, System.nanoTime())
            }
        }

        log.debug { "Kubernetes Lease group acquisition skipped. lockName=$lockName" }
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
                log.warn(e) { "Kubernetes Lease group state query failed. lockName=$lockName, slot=$slot" }
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
                factoryBeanName = K8S_GROUP_FACTORY_BEAN_NAME,
                groupParams = LockIdentity.GroupParams(maxLeaders),
            ),
            token = lock.ownerToken,
            acquiredAtNanos = acquiredAtNanos,
            slotId = slot.toString(),
            extendDelegate = delegate,
            auditLeaderId = auditLeaderId ?: lock.ownerToken,
        )

    private fun release(
        lock: KubernetesLeaseLock,
        acquiredAtNanos: Long,
        lockName: String,
        slot: Int,
    ): Boolean =
        try {
            lock.unlock(options.leaderGroupOptions.minLeaseTime, acquiredAtNanos)
        } catch (e: Exception) {
            log.warn(e) { "Failed to release Kubernetes Lease group slot. lockName=$lockName, slot=$slot" }
            false
        }

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

private fun Throwable?.unwrapCompletionException(): Throwable? =
    if (this is CompletionException && cause != null) cause else this

/**
 * Runs [action] only when this client acquires one Kubernetes Lease group slot.
 */
inline fun <T> KubernetesClient.runIfLeaderGroup(
    lockName: String,
    options: KubernetesLeaseGroupOptions = KubernetesLeaseGroupOptions.Default,
    crossinline action: () -> T,
): T? =
    KubernetesLeaseLeaderGroupElector(this, options).runIfLeader(lockName) { action() }

/**
 * Runs [action] asynchronously only when this client acquires one Kubernetes Lease group slot.
 */
fun <T> KubernetesClient.runAsyncIfLeaderGroup(
    lockName: String,
    options: KubernetesLeaseGroupOptions = KubernetesLeaseGroupOptions.Default,
    executor: Executor = VirtualThreadExecutor,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    KubernetesLeaseLeaderGroupElector(this, options).runAsyncIfLeader(lockName, executor, action)
