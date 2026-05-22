package io.bluetape4k.leader.consul.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

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
            lockClient.renewSession(handle.sessionId).get(10, TimeUnit.SECONDS)
            val deadline = Instant.now().plusMillis(lockAtMostFor.inWholeMilliseconds)
            _lastExtendDeadline.set(deadline)
            ExtendOutcome.Extended(deadline)
        }.getOrElse { e ->
            ExtendOutcome.BackendError(e.asException())
        }
    }

    override fun isHeld(): Boolean =
        !handle.isReleased && runCatching {
            lockClient.read(handle.key).get(10, TimeUnit.SECONDS)?.sessionId == handle.sessionId
        }.getOrElse { e ->
            log.warn(e) { "Failed to verify Consul leader session ownership. lockName=${handle.lockName}" }
            false
        }

    override val lastExtendDeadline: AtomicReference<Instant>
        get() = _lastExtendDeadline
}

private fun Throwable.asException(): Exception =
    this as? Exception ?: RuntimeException(this)
