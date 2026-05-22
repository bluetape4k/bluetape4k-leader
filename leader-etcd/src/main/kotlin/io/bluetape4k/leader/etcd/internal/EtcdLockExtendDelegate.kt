package io.bluetape4k.leader.etcd.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

internal class EtcdLockExtendDelegate(
    private val lockClient: EtcdLockClient,
    private val handle: EtcdLeaseHandle,
) : ExtendDelegate {

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)

    override fun extend(lockAtMostFor: Duration): ExtendOutcome {
        if (handle.isReleased) {
            return ExtendOutcome.NotHeld
        }

        return runCatching {
            lockClient.keepAliveOnce(handle.leaseId).get(10, TimeUnit.SECONDS)
            ExtendOutcome.Extended(Instant.now().plusMillis(lockAtMostFor.inWholeMilliseconds))
        }.getOrElse { e ->
            if (EtcdBackendErrorClassifier.isExpectedCleanup(e)) {
                ExtendOutcome.NotHeld
            } else {
                ExtendOutcome.BackendError(e.asException())
            }
        }
    }

    override fun isHeld(): Boolean =
        !handle.isReleased && runCatching {
            lockClient.keepAliveOnce(handle.leaseId).get(10, TimeUnit.SECONDS)
            true
        }.getOrDefault(false)

    override val lastExtendDeadline: AtomicReference<Instant>
        get() = _lastExtendDeadline
}

internal class EtcdSuspendLockExtendDelegate(
    private val lockClient: EtcdLockClient,
    private val handle: EtcdLeaseHandle,
) : SuspendExtendDelegate {

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome {
        if (handle.isReleased) {
            return ExtendOutcome.NotHeld
        }

        return try {
            lockClient.keepAliveOnce(handle.leaseId).await()
            ExtendOutcome.Extended(Instant.now().plusMillis(lockAtMostFor.inWholeMilliseconds))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (EtcdBackendErrorClassifier.isExpectedCleanup(e)) {
                ExtendOutcome.NotHeld
            } else {
                ExtendOutcome.BackendError(e)
            }
        }
    }

    override suspend fun isHeldSuspend(): Boolean =
        !handle.isReleased && try {
            lockClient.keepAliveOnce(handle.leaseId).await()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }

    override val lastExtendDeadline: AtomicReference<Instant>
        get() = _lastExtendDeadline
}

private fun Throwable.asException(): Exception =
    this as? Exception ?: RuntimeException(this)
