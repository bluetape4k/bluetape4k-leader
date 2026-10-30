package io.bluetape4k.leader.consul.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * `ConsulSuspendLockExtendDelegate`는 Consul session renew와 KV session 확인을 suspend watchdog 계약으로 연결합니다.
 *
 * @property lockClient suspend session renew와 KV owner 확인을 Consul API에 위임하는 클라이언트입니다.
 * @property handle 현재 session·KV key·owner token을 보유한 lease 핸들입니다.
 */
internal class ConsulSuspendLockExtendDelegate(
    private val lockClient: ConsulLockClient,
    private val handle: ConsulLeaseHandle,
): SuspendExtendDelegate {

    companion object: KLoggingChannel()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome {
        if (handle.isReleased) {
            return ExtendOutcome.NotHeld
        }

        return try {
            lockClient.renewSession(handle.sessionId).await()
            if (lockClient.read(handle.key).await()?.sessionId != handle.sessionId) {
                ExtendOutcome.NotHeld
            } else {
                val deadline = Instant.now().plusMillis(lockAtMostFor.inWholeMilliseconds)
                _lastExtendDeadline.set(deadline)
                ExtendOutcome.Extended(deadline)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ExtendOutcome.BackendError(e)
        }
    }

    override suspend fun isHeldSuspend(): Boolean =
        !handle.isReleased &&
                try {
                    lockClient.read(handle.key).await()?.sessionId == handle.sessionId
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "Consul suspend leader ownership check failed. lockName=${handle.lockName}" }
                    false
                }

    override val lastExtendDeadline: AtomicReference<Instant>
        get() = _lastExtendDeadline
}
