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
 * `KubernetesLeaseLockExtendDelegate`는 Kubernetes Lease backend의 lease, ownership 확인, session/TTL 정리를 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property lock Kubernetes Lease backend 호출과 상태 계산에 사용하는 속성입니다.
 */
internal class KubernetesLeaseLockExtendDelegate(
    private val lock: KubernetesLeaseLock,
) : SuspendExtendDelegate {
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
