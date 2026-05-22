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
            val deadline = Instant.now().plusMillis(lockAtMostFor.inWholeMilliseconds)
            _lastExtendDeadline.set(deadline)
            ExtendOutcome.Extended(deadline)
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
