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
 * Extend delegate for the Kubernetes Lease backend.
 *
 * ## Behavior / Contract
 *
 * Implements [SuspendExtendDelegate] so that [io.bluetape4k.leader.LeaderLeaseAutoExtender] selects
 * the suspend watchdog overload and dispatches all Fabric8 I/O into [Dispatchers.IO].
 *
 * The sync [extend] and [isHeld] overrides keep the blocking elector ([KubernetesLeaseLeaderElector])
 * functional without going through `runBlocking`.
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
