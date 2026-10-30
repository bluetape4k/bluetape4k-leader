package io.bluetape4k.leader.consul.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * `ConsulLockExtendDelegate`는 Consul session renew와 KV session 확인을 동기 watchdog 계약으로 연결합니다.
 *
 * @property lockClient session renew와 KV owner 확인을 Consul API에 위임하는 클라이언트입니다.
 * @property handle 현재 session·KV key·owner token을 보유한 lease 핸들입니다.
 */
internal class ConsulLockExtendDelegate(
    private val lockClient: ConsulLockClient,
    private val handle: ConsulLeaseHandle,
): ExtendDelegate {

    companion object: KLogging()

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
        !handle.isReleased &&
                runCatching {
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
