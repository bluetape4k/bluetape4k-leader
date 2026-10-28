package io.bluetape4k.leader.k8s

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirer
import io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirerSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.internal.SuspendLeaderElectorLeaseAdapter
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
import io.bluetape4k.leader.k8s.suspendRunIfLeader as currentSuspendRunIfLeader

/**
 * `KubernetesLeaseSuspendLeaderElector`는 namespaced Kubernetes Lease로 단일 leader의 suspend 실행과 lease 수명을 관리합니다.
 *
 * @property client 지정 namespace의 Lease를 생성, 조회, 갱신, 삭제하는 Fabric8 Kubernetes client입니다.
 * @property options namespace, 충돌 재시도 지연, 대기/Lease 유지 시간과 자동 연장을 제공하는 단일 리더 옵션입니다.
 * @property clock Lease의 acquire/renew 시각과 만료 여부를 계산하는 시계이며 기본값은 UTC 시계입니다.
 */
class KubernetesLeaseSuspendLeaderElector @JvmOverloads constructor(
    private val client: KubernetesClient,
    val options: KubernetesLeaseOptions = KubernetesLeaseOptions.Default,
    private val clock: Clock = Clock.systemUTC(),
): SuspendLeaderElector,
   LeaderBackendDiagnosticsProvider by KubernetesLeaderBackendDiagnostics,
   SuspendLeaderLeaseAcquirerSupport {

    override val suspendLeaseAcquirerDelegate: SuspendLeaderLeaseAcquirer by lazy {
        SuspendLeaderElectorLeaseAdapter({ this }, options.leaderOptions)
    }

    companion object: KLoggingChannel() {
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

    private suspend inline fun <T> runWithLock(
        lockName: String,
        auditLeaderId: String?,
        crossinline action: suspend () -> T,
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
                watchdog?.let { LeaderLeaseAutoExtender.closeSuspend(it) }
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

/** 리팩터링 전 JVM facade의 바이너리 호환성을 보존하는 shim입니다. */
@Deprecated("리팩터링 전 JVM facade 호환성 유지용", level = DeprecationLevel.HIDDEN)
@JvmName("suspendRunIfLeader")
suspend fun <T> KubernetesClient.legacySuspendRunIfLeader(
    lockName: String,
    options: KubernetesLeaseOptions = KubernetesLeaseOptions.Default,
    action: suspend () -> T,
): T? = this.currentSuspendRunIfLeader(lockName, options, action)
