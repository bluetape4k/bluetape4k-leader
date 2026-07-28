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
 * `ConsulSuspendLockExtendDelegate`는 Consul backend의 lease, session/TTL, owner 검증 상태를 보존하는 내부 class입니다.
 *
 * backend의 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 유지합니다.
 * @property lockClient Consul backend 계약에서 `lockClient` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property handle Consul backend 계약에서 `handle` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
internal class ConsulSuspendLockExtendDelegate(
    private val lockClient: ConsulLockClient,
    private val handle: ConsulLeaseHandle,
) : SuspendExtendDelegate {

    companion object : KLoggingChannel()

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
        !handle.isReleased && try {
            lockClient.read(handle.key).await()?.sessionId == handle.sessionId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Failed to verify Consul suspend leader session ownership. lockName=${handle.lockName}" }
            false
        }

    override val lastExtendDeadline: AtomicReference<Instant>
        get() = _lastExtendDeadline
}
