package io.bluetape4k.leader.k8s.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * `KubernetesLeaseLockExtendDelegate`는 Kubernetes Lease의 연장과 owner 보유 확인을 sync/suspend watchdog에 제공합니다.
 *
 * @property lock 동기/중단 가능한 Lease 연장과 현재 owner 보유 여부를 위임하는 실제 Kubernetes Lease lock입니다.
 */
internal class KubernetesLeaseLockExtendDelegate(
    private val lock: KubernetesLeaseLock,
): SuspendExtendDelegate {
    private val lastDeadline = AtomicReference(Instant.EPOCH)

    override val lastExtendDeadline: AtomicReference<Instant> get() = lastDeadline

    // Overridden explicitly so that the blocking elector (KubernetesLeaseLeaderElector) continues to
    // call Fabric8 I/O correctly. SuspendExtendDelegate.extend() defaults to BackendError, which
    // would break the sync watchdog.
    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        lock.extendDetailed(lockAtMostFor)

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome {
        currentCoroutineContext().ensureActive()
        return withContext(Dispatchers.IO) {
            lock.extendDetailed(lockAtMostFor)
        }
    }

    // Overridden explicitly for the same reason as extend() above.
    override fun isHeld(): Boolean =
        lock.isHeldByCurrentInstance()

    override suspend fun isHeldSuspend(): Boolean =
        withContext(Dispatchers.IO) {
            lock.isHeldByCurrentInstance()
        }
}
