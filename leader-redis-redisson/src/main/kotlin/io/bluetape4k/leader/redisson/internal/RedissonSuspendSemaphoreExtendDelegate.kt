package io.bluetape4k.leader.redisson.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.BackendErrorKind
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.redisson.api.RPermitExpirableSemaphore
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

/**
 * [SuspendExtendDelegate] for Redisson [RPermitExpirableSemaphore] (suspend group) — T8 PR 3 (Issue #79).
 *
 * Redisson's async API ([RPermitExpirableSemaphore.updateLeaseTimeAsync]) is [java.util.concurrent.CompletableFuture]-based,
 * so it is bridged to suspend via `await()`.
 *
 * ## Behavior / Contract
 * - [extendSuspend]: delegates to `semaphore.updateLeaseTimeAsync(permitId, ms, MILLISECONDS).await()`.
 *   - success (`true`) → [ExtendOutcome.Extended]; [active] stays `true`
 *   - failure (`false`) → [ExtendOutcome.NotHeld]; [active] transitions to `false`
 *   - exception → classifies backend kind; keeps active on transient, sets false on non-transient/FATAL
 * - [isHeldSuspend]: reads the local [AtomicBoolean] flag directly (Redisson API does not support this query).
 *
 * @property semaphore Redisson [RPermitExpirableSemaphore]
 * @property permitId permit identifier issued by Redisson
 */
internal class RedissonSuspendSemaphoreExtendDelegate(
    private val semaphore: RPermitExpirableSemaphore,
    private val permitId: String,
): SuspendExtendDelegate {

    companion object: KLoggingChannel()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    /**
     * Whether the permit is held — starts as `true` on acquire. Transitions to `false` based on [extendSuspend] results.
     */
    private val active = AtomicBoolean(true)

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        try {
            doExtendSuspend(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) {
                "RedissonSuspend group extendSuspend failed. semaphore=${semaphore.name}, permitId=$permitId"
            }
            updateActiveOnError(e)
            ExtendOutcome.BackendError(e)
        }
    }

    /**
     * Reads the local [active] flag directly — a workaround for Redisson's lack of a per-permitId query API.
     *
     * For diagnostic use only — for a strong guarantee, check [extendSuspend] results directly.
     */
    override suspend fun isHeldSuspend(): Boolean = active.get()

    private suspend fun doExtendSuspend(lockAtMostFor: Duration): ExtendOutcome {
        val ms = lockAtMostFor.inWholeMilliseconds
        val updated = semaphore.updateLeaseTimeAsync(permitId, ms, TimeUnit.MILLISECONDS)
            .toCompletableFuture()
            .await()
        return if (updated) {
            ExtendOutcome.Extended(Instant.now().plusMillis(ms))
        } else {
            // permit 만료 또는 takeover — active 플래그 false 로 전이.
            active.set(false)
            ExtendOutcome.NotHeld
        }
    }

    private fun updateActiveOnError(cause: Throwable) {
        val kind = RedissonBackendErrorClassifier.classify(cause)
        if (kind == BackendErrorKind.NON_TRANSIENT || kind == BackendErrorKind.FATAL) {
            active.set(false)
        }
    }
}
