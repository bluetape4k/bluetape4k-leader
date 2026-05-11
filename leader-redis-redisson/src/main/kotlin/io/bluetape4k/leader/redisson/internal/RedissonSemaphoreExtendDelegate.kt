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
 * Redisson [RPermitExpirableSemaphore] (sync group) 용 [ExtendDelegate] — T8 PR 3 (Issue #79).
 *
 * ## 동작/계약
 * - [extend] : `semaphore.updateLeaseTime(permitId, ms, MILLISECONDS)` 위임.
 *   - `true` → [ExtendOutcome.Extended], 내부 [active] 플래그 그대로 유지(true)
 *   - `false` → [ExtendOutcome.NotHeld], [active] 플래그 false 로 전이
 *   - exception → backend kind 분류 후 transient 면 active 유지, non-transient/FATAL 이면 false 로 전이
 * - [extendSuspend] : Redisson sync facade — `withContext(Dispatchers.IO)` + `ensureActive()` (R9 / AC-21).
 * - [isHeld] : 로컬 [AtomicBoolean] 플래그 직접 조회. Redisson `RPermitExpirableSemaphore` 는 permitId
 *   보유 여부를 비파괴적으로 조회하는 API 미제공 — `updateLeaseTime(1ms)` 같은 probe 는 lease 를
 *   파괴적으로 단축하므로 사용 금지. 대신 [extend] 결과를 단일 진실원으로 추적.
 *
 * ## acceptable race window
 * `extend` 가 마지막으로 `Extended` 를 반환한 후, backend 측에서 lease 만료 / takeover 가 발생하기까지
 * 본 delegate 의 [isHeld] 는 잠시 `true` 를 반환할 수 있다. 다음 [extend] 호출 시점에 false 로 정정된다.
 * Diagnostic 용도만 사용 — 락 보유 여부의 강한 보장이 필요하면 [extend] 결과를 직접 사용.
 *
 * AC-16: server-side TIME (Redisson 내부 Lua) — client clock skew 차단.
 * AC-21: blocking backend [ExtendDelegate.extendSuspend] override.
 *
 * @property semaphore Redisson [RPermitExpirableSemaphore]
 * @property permitId Redisson 발급 permit identifier
 */
internal class RedissonSemaphoreExtendDelegate(
    private val semaphore: RPermitExpirableSemaphore,
    private val permitId: String,
): ExtendDelegate {

    companion object: KLogging()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    /**
     * permit 보유 여부 — acquire 시 true 로 시작. [extend] 결과에 따라 false 로 전이.
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
     * 로컬 [active] 플래그 직접 조회 — Redisson 의 permitId 단위 조회 API 부재에 대한 우회.
     *
     * **race window** : backend 측 만료/takeover 발생 ~ 다음 [extend] 호출 사이에 stale `true` 반환 가능.
     * Diagnostic 용도만 사용. 강한 보장 필요 시 [extend] 결과로 직접 확인.
     */
    override fun isHeld(): Boolean = active.get()
}
