package io.bluetape4k.leader.k8s

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
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
 * `KubernetesLeaseSuspendLeaderElector`는 Kubernetes Lease backend의 lease, ownership 확인, session/TTL 정리를 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property client Kubernetes Lease backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property options Kubernetes Lease backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property clock Kubernetes Lease backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class KubernetesLeaseSuspendLeaderElector @JvmOverloads constructor(
    private val client: KubernetesClient,
    val options: KubernetesLeaseOptions = KubernetesLeaseOptions.Default,
    private val clock: Clock = Clock.systemUTC(),
) : SuspendLeaderElector,
    LeaderBackendDiagnosticsProvider by KubernetesLeaderBackendDiagnostics,
    io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirerSupport {

    override val suspendLeaseAcquirerDelegate: io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirer by lazy {
        io.bluetape4k.leader.internal.SuspendLeaderElectorLeaseAdapter({ this }, options.leaderOptions)
    }

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

    override val supportsAuditLeaderState: Boolean = true

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
        var watchdog: AutoCloseable? = null

        try {
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
            watchdog = LeaderLeaseAutoExtender.start(
                options.leaderOptions.autoExtend,
                options.leaderOptions.leaseTime,
                delegate,
                KubernetesLeaseLeaderElector.ERROR_CLASSIFIER,
            )
            return withContext(AopScopeAccess.createLockHandleElement(handle)) {
                action()
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                watchdog?.close()
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
 * `선언` 호출은 Kubernetes Lease backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lease`, `session`, `TTL`, `owner`, `annotation`, `cleanup` 용어는 backend 계약과 동일하게 유지합니다.
 */
suspend fun <T> KubernetesClient.suspendRunIfLeader(
    lockName: String,
    options: KubernetesLeaseOptions = KubernetesLeaseOptions.Default,
    action: suspend () -> T,
): T? = KubernetesLeaseSuspendLeaderElector(this, options).runIfLeader(lockName, action)
