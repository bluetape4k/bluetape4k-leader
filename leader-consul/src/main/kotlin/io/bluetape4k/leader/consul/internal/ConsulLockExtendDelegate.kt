package io.bluetape4k.leader.consul.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * `ConsulLockExtendDelegate`는 Consul backend의 lease, session/TTL, owner 검증 상태를 보존하는 내부 class입니다.
 *
 * backend의 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 유지합니다.
 * @property lockClient Consul backend 계약에서 `lockClient` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property handle Consul backend 계약에서 `handle` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
internal class ConsulLockExtendDelegate(
    private val lockClient: ConsulLockClient,
    private val handle: ConsulLeaseHandle,
) : ExtendDelegate {

    companion object : KLogging()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)

    override fun extend(lockAtMostFor: Duration): ExtendOutcome {
        if (handle.isReleased) {
            return ExtendOutcome.NotHeld
        }

        return runCatching {
            lockClient.renewSession(handle.sessionId).getWithinRequestTimeout(lockClient)
            if (lockClient.read(handle.key).getWithinRequestTimeout(lockClient)?.sessionId != handle.sessionId) {
                ExtendOutcome.NotHeld
            } else {
                val deadline = Instant.now().plusMillis(lockAtMostFor.inWholeMilliseconds)
                _lastExtendDeadline.set(deadline)
                ExtendOutcome.Extended(deadline)
            }
        }.getOrElse { e ->
            ExtendOutcome.BackendError(e.asException())
        }
    }

    override fun isHeld(): Boolean =
        !handle.isReleased && runCatching {
            lockClient.read(handle.key).getWithinRequestTimeout(lockClient)?.sessionId == handle.sessionId
        }.getOrElse { e ->
            log.warn(e) { "Failed to verify Consul leader session ownership. lockName=${handle.lockName}" }
            false
        }

    override val lastExtendDeadline: AtomicReference<Instant>
        get() = _lastExtendDeadline
}

private fun Throwable.asException(): Exception =
    this as? Exception ?: RuntimeException(this)
