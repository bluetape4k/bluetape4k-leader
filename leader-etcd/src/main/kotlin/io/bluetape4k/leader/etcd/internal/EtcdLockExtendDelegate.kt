package io.bluetape4k.leader.etcd.internal

import io.bluetape4k.concurrent.get
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.etcd.jetcd.lease.LeaseKeepAliveResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * `EtcdLockExtendDelegate`는 etcd lease keep-alive 결과를 동기 watchdog의 연장/보유 상태로 변환합니다.
 *
 * @property lockClient keep-alive 요청을 etcd에 전달하는 클라이언트입니다.
 * @property handle lease ID·ownership key·반납 상태를 가진 현재 획득 핸들입니다.
 */
internal class EtcdLockExtendDelegate(
    private val lockClient: EtcdLockClient,
    private val handle: EtcdLeaseHandle,
): ExtendDelegate {

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)

    override fun extend(lockAtMostFor: Duration): ExtendOutcome {
        if (handle.isReleased) {
            return ExtendOutcome.NotHeld
        }

        return runCatching {
            lockClient.keepAliveOnce(handle.leaseId).get(10.seconds).toExtendOutcome()
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
            lockClient.keepAliveOnce(handle.leaseId).get(10.seconds).getTTL() > 0L
        }.getOrDefault(false)

    override val lastExtendDeadline: AtomicReference<Instant>
        get() = _lastExtendDeadline
}

/**
 * `EtcdSuspendLockExtendDelegate`는 etcd lease keep-alive 결과를 suspend watchdog의 연장/보유 상태로 변환합니다.
 *
 * @property lockClient suspend keep-alive 요청을 etcd에 전달하는 클라이언트입니다.
 * @property handle lease ID·ownership key·반납 상태를 가진 현재 획득 핸들입니다.
 */
internal class EtcdSuspendLockExtendDelegate(
    private val lockClient: EtcdLockClient,
    private val handle: EtcdLeaseHandle,
): SuspendExtendDelegate {

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
            lockClient.keepAliveOnce(handle.leaseId).await().ttl > 0L
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
    val ttlSeconds = ttl
    return if (ttlSeconds > 0L) {
        ExtendOutcome.Extended(Instant.now().plusSeconds(ttlSeconds))
    } else {
        ExtendOutcome.NotHeld
    }
}
