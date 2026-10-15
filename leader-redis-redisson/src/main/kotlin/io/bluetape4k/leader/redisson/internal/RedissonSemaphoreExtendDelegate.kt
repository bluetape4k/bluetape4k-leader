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
import kotlin.time.Duration

/**
 * `RedissonSemaphoreExtendDelegate`는 Redis Redisson 그룹 slot/permit의 lease 연장과 현재 ownership 확인을 elector에서 분리해 위임합니다.
 *
 * @property semaphore 그룹 lease를 갱신하는 Redisson expirable permit semaphore입니다.
 * @property permitId semaphore가 발급한 slot ownership token입니다. Redis ownership 확인과 release/lease 연장에 사용되므로 외부 계약에서는 민감한 값으로 취급해야 합니다.
 */
internal class RedissonSemaphoreExtendDelegate(
    private val semaphore: RPermitExpirableSemaphore,
    private val permitId: String,
): ExtendDelegate {

    companion object: KLogging()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    /** permit이 만료·탈취되거나 비재시도 오류가 나면 `false`로 전이하는 로컬 보유 상태입니다. */
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

    /** 마지막 lease 연장 결과를 반영한 로컬 permit 보유 상태를 반환합니다. */
    override fun isHeld(): Boolean = active.get()
}
