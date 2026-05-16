package io.bluetape4k.leader.redisson.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.BackendErrorKind
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.redisson.api.RPermitExpirableSemaphore
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

/**
 * [ExtendDelegate] for Redisson [RPermitExpirableSemaphore] (sync group) — T8 PR 3 (Issue #79).
 *
 * ## Behavior / Contract
 * - [extend]: delegates to `semaphore.updateLeaseTime(permitId, ms, MILLISECONDS)`.
 *   - `true` → [ExtendOutcome.Extended]; the internal [active] flag stays `true`
 *   - `false` → [ExtendOutcome.NotHeld]; transitions [active] flag to `false`
 *   - exception → classifies backend kind; keeps active on transient, sets false on non-transient/FATAL
 * - [extendSuspend]: Redisson sync facade — dispatched with `withContext(Dispatchers.IO)` + `ensureActive()` (R9 / AC-21).
 * - [isHeld]: reads the local [AtomicBoolean] flag directly. Redisson `RPermitExpirableSemaphore`
 *   provides no non-destructive API to query permitId ownership — a probe like `updateLeaseTime(1ms)`
 *   would destructively shorten the lease and must not be used. [extend] results are the single source of truth.
 *
 * ## Acceptable race window
 * Between the last `Extended` result from [extend] and the next backend lease expiry / takeover,
 * [isHeld] may momentarily return `true`. It is corrected to `false` on the next [extend] call.
 * Use for diagnostic purposes only — for a strong guarantee, check [extend] results directly.
 *
 * AC-16: server-side TIME (Redisson internal Lua) — blocks client clock skew.
 * AC-21: blocking backend [ExtendDelegate.extendSuspend] override.
 *
 * @property semaphore Redisson [RPermitExpirableSemaphore]
 * @property permitId permit identifier issued by Redisson
 */
internal class RedissonSemaphoreExtendDelegate(
    private val semaphore: RPermitExpirableSemaphore,
    private val permitId: String,
): ExtendDelegate {

    companion object: KLogging()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    /**
     * Whether the permit is held — starts as `true` on acquire. Transitions to `false` based on [extend] results.
     */
    private val active = AtomicBoolean(true)

    override fun extend(lockAtMostFor: Duration): ExtendOutcome {
        val ms = lockAtMostFor.inWholeMilliseconds
        return try {
            val updated = semaphore.updateLeaseTime(permitId, ms, TimeUnit.MILLISECONDS)
            if (updated) {
                ExtendOutcome.Extended(Instant.now().plusMillis(ms))
            } else {
                // permit 만료 또는 takeover — active 플래그 false 로 전이.
                active.set(false)
                ExtendOutcome.NotHeld
            }
        } catch (e: Exception) {
            log.warn(e) { "Redisson group updateLeaseTime failed. semaphore=${semaphore.name}, permitId=$permitId" }
            // non-transient/FATAL 이면 active 플래그 false 전이. transient 면 retry 가능 — 유지.
            val kind = RedissonBackendErrorClassifier.classify(e)
            if (kind == BackendErrorKind.NON_TRANSIENT || kind == BackendErrorKind.FATAL) {
                active.set(false)
            }
            ExtendOutcome.BackendError(e)
        }
    }

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        try {
            extend(lockAtMostFor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) {
                "Redisson group updateLeaseTimeSuspend failed. semaphore=${semaphore.name}, permitId=$permitId"
            }
            ExtendOutcome.BackendError(e)
        }
    }

    /**
     * Reads the local [active] flag directly — a workaround for Redisson's lack of a non-destructive
     * per-permitId query API.
     *
     * **Race window**: may return a stale `true` between a backend expiry/takeover and the next [extend] call.
     * For diagnostic use only. For a strong guarantee, check [extend] results directly.
     */
    override fun isHeld(): Boolean = active.get()
}
