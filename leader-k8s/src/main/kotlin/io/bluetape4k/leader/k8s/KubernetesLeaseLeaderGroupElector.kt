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
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.internal.LeaderFutureBridge
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * `KubernetesLeaseLeaderGroupElector`는 Kubernetes Lease backend의 lease, ownership 확인, session/TTL 정리를 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property client Kubernetes Lease backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property options Kubernetes Lease backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property clock Kubernetes Lease backend 호출과 상태 계산에 사용하는 속성입니다.
 */
// Group elector는 blocking/async, 상태 조회, lease lifecycle, diagnostics 계약을 함께 구현합니다.
@Suppress("TooManyFunctions")
class KubernetesLeaseLeaderGroupElector @JvmOverloads constructor(
    private val client: KubernetesClient,
    val options: KubernetesLeaseGroupOptions = KubernetesLeaseGroupOptions.Default,
    private val clock: Clock = Clock.systemUTC(),
) : LeaderGroupElector,
    LeaderBackendDiagnosticsProvider by KubernetesLeaderBackendDiagnostics {

    companion object : KLogging() {
        internal const val K8S_GROUP_FACTORY_BEAN_NAME = "kubernetes-lease-leader-group-elector"
    }

    private enum class AsyncLifecycle {
        WAITING,
        STARTED,
        CLEANUP,
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
        val cancellationRelay = LeaderFutureBridge.cancellationRelay()
        return LeaderFutureBridge.map(runAsyncWithGroupSlot(slot.lockName, slot.leaderId, executor) {
            elected = true
            cancellationRelay.invoke(action)
        }, cancellationRelay) { value, failure ->
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

    @Suppress("TooGenericExceptionCaught")
    private fun <T> runAsyncWithGroupSlot(
        lockName: String,
        auditLeaderId: String?,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val acquiredRef = AtomicReference<AcquiredSlot?>()
        val lifecycle = AtomicReference(AsyncLifecycle.WAITING)
        val releaseIfUnclaimed: () -> Unit = {
            val acquired = acquiredRef.get()
            if (acquired != null && lifecycle.compareAndSet(AsyncLifecycle.WAITING, AsyncLifecycle.CLEANUP)) {
                release(acquired.lock, acquired.acquiredAtNanos, lockName, acquired.slot)
            }
        }
        val acquisitionFuture = CompletableFuture.supplyAsync({
            acquire(lockName, auditLeaderId).also { acquired ->
                if (acquired != null) acquiredRef.set(acquired)
            }
        }, executor)
        val pipelineFuture: CompletableFuture<T?> = try {
            acquisitionFuture.thenComposeAsync({ acquired ->
                if (acquired == null) {
                    CompletableFuture.completedFuture(null)
                } else if (!lifecycle.compareAndSet(AsyncLifecycle.WAITING, AsyncLifecycle.STARTED)) {
                    CompletableFuture.failedFuture(
                        CancellationException("leader result future was cancelled before action"),
                    )
                } else {
                    try {
                        runAcquiredAsync(lockName, acquired, auditLeaderId, executor, action)
                    } catch (error: Throwable) {
                        release(acquired.lock, acquired.acquiredAtNanos, lockName, acquired.slot)
                        CompletableFuture.failedFuture(error)
                    }
                }
            }, executor)
        } catch (error: Throwable) {
            acquisitionFuture.whenComplete { acquired, _ ->
                if (acquired != null) releaseIfUnclaimed()
            }
            CompletableFuture.failedFuture(error)
        }
        pipelineFuture.whenComplete { _, failure ->
            if (failure != null) releaseIfUnclaimed()
        }
        acquisitionFuture.whenComplete { acquired, _ ->
            if (acquired != null && pipelineFuture.isCancelled) releaseIfUnclaimed()
        }
        return pipelineFuture
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun <T> runAcquiredAsync(
        lockName: String,
        acquired: AcquiredSlot,
        auditLeaderId: String?,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val lock = acquired.lock
        val delegate = KubernetesLeaseLockExtendDelegate(lock)
        val watchdog = try {
            LeaderLeaseAutoExtender.start(
                enabled = false,
                leaseTime = options.leaderGroupOptions.leaseTime,
                delegate = delegate,
                classifier = KubernetesLeaseLeaderElector.ERROR_CLASSIFIER,
            )
        } catch (e: Throwable) {
            release(lock, acquired.acquiredAtNanos, lockName, acquired.slot)
            return CompletableFuture.failedFuture(e)
        }
        val actionFuture = try {
            val handle = handle(lockName, lock, acquired.slot, acquired.acquiredAtNanos, delegate, auditLeaderId)
            AopScopeAccess.withPushedSync(handle) { action() }
        } catch (e: Throwable) {
            watchdog.close()
            release(lock, acquired.acquiredAtNanos, lockName, acquired.slot)
            return CompletableFuture.failedFuture(e)
        }

        return actionFuture.handle { value, failure ->
            watchdog.close()
            release(lock, acquired.acquiredAtNanos, lockName, acquired.slot)
            val cause = failure.unwrapCompletionException()
            if (cause != null) {
                throw cause
            }
            value
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
 * `선언` 호출은 Kubernetes Lease backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lease`, `session`, `TTL`, `owner`, `annotation`, `cleanup` 용어는 backend 계약과 동일하게 유지합니다.
 */
inline fun <T> KubernetesClient.runIfLeaderGroup(
    lockName: String,
    options: KubernetesLeaseGroupOptions = KubernetesLeaseGroupOptions.Default,
    crossinline action: () -> T,
): T? =
    KubernetesLeaseLeaderGroupElector(this, options).runIfLeader(lockName) { action() }

/**
 * `선언` 호출은 Kubernetes Lease backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lease`, `session`, `TTL`, `owner`, `annotation`, `cleanup` 용어는 backend 계약과 동일하게 유지합니다.
 */
fun <T> KubernetesClient.runAsyncIfLeaderGroup(
    lockName: String,
    options: KubernetesLeaseGroupOptions = KubernetesLeaseGroupOptions.Default,
    executor: Executor = VirtualThreadExecutor,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    KubernetesLeaseLeaderGroupElector(this, options).runAsyncIfLeader(lockName, executor, action)
