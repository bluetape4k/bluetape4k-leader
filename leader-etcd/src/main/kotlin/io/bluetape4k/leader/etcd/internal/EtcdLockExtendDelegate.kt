package io.bluetape4k.leader.etcd.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.etcd.jetcd.lease.LeaseKeepAliveResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * `EtcdLockExtendDelegate`는 etcd backend의 lease, session/TTL, owner 검증 상태를 보존하는 내부 class입니다.
 *
 * backend의 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 유지합니다.
 * @property lockClient etcd backend 계약에서 `lockClient` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property handle etcd backend 계약에서 `handle` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
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
            lockClient.keepAliveOnce(handle.leaseId).get(10, TimeUnit.SECONDS).toExtendOutcome()
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
            lockClient.keepAliveOnce(handle.leaseId).get(10, TimeUnit.SECONDS).getTTL() > 0L
        }.getOrDefault(false)

    override val lastExtendDeadline: AtomicReference<Instant>
        get() = _lastExtendDeadline
}

/**
 * `EtcdSuspendLockExtendDelegate`는 etcd backend의 lease, session/TTL, owner 검증 상태를 보존하는 내부 class입니다.
 *
 * backend의 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 유지합니다.
 * @property lockClient etcd backend 계약에서 `lockClient` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property handle etcd backend 계약에서 `handle` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
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
            lockClient.keepAliveOnce(handle.leaseId).await().toExtendOutcome()
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
            lockClient.keepAliveOnce(handle.leaseId).await().getTTL() > 0L
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

private fun LeaseKeepAliveResponse.toExtendOutcome(): ExtendOutcome {
    val ttlSeconds = getTTL()
    return if (ttlSeconds > 0L) {
        ExtendOutcome.Extended(Instant.now().plusSeconds(ttlSeconds))
    } else {
        ExtendOutcome.NotHeld
    }
}
