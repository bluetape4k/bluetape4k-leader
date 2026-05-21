package io.bluetape4k.leader.k8s.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

internal class KubernetesLeaseLockExtendDelegate(
    private val lock: KubernetesLeaseLock,
) : ExtendDelegate {
    private val lastDeadline = AtomicReference(Instant.EPOCH)

    override val lastExtendDeadline: AtomicReference<Instant> get() = lastDeadline

    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        lock.extendDetailed(lockAtMostFor)

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome {
        currentCoroutineContext().ensureActive()
        return withContext(Dispatchers.IO) {
            lock.extendDetailed(lockAtMostFor)
        }
    }

    override fun isHeld(): Boolean =
        lock.isHeldByCurrentInstance()
}
