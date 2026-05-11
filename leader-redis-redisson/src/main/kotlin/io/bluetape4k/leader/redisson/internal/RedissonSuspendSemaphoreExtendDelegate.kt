package io.bluetape4k.leader.redisson.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.BackendErrorKind
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.redisson.api.RPermitExpirableSemaphore
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

/**
 * Redisson [RPermitExpirableSemaphore] (suspend group) 용 [ExtendDelegate] — T8 PR 3 (Issue #79).
 *
 * Redisson 의 async API ([RPermitExpirableSemaphore.updateLeaseTimeAsync]) 는 [java.util.concurrent.CompletableFuture]
 * 기반이므로 `await()` 으로 suspend bridge.
 *
 * ## 동작/계약
 * - [extendSuspend] : `semaphore.updateLeaseTimeAsync(permitId, ms, MILLISECONDS).await()` 위임.
 *   - 성공(true) → [ExtendOutcome.Extended], [active] 그대로 유지(true)
 *   - 실패(false) → [ExtendOutcome.NotHeld], [active] false 로 전이
 *   - exception → backend kind 분류 후 transient 면 active 유지, non-transient/FATAL 이면 false 로 전이
 * - [extend] (sync) : suspend 함수만 노출되므로 `runBlocking` 으로 bridge — watchdog scheduler thread 호출 안전.
 * - [isHeld] : 로컬 [AtomicBoolean] 플래그 직접 조회 (sync, Redisson API 미지원).
 *
 * @property semaphore Redisson [RPermitExpirableSemaphore]
 * @property permitId Redisson 발급 permit identifier
 */
internal class RedissonSuspendSemaphoreExtendDelegate(
    private val semaphore: RPermitExpirableSemaphore,
    private val permitId: String,
): ExtendDelegate {

    companion object: KLoggingChannel()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    /**
     * permit 보유 여부 — acquire 시 true 로 시작. [extendSuspend] 결과에 따라 false 로 전이.
     */
    private val active = AtomicBoolean(true)

    /**
     * sync entry point — watchdog scheduler thread 에서 호출됨.
     *
     * Redisson async 는 Netty 기반이므로 `runBlocking` 은 backpressure 없이 안전.
     */
    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            runBlocking { doExtendSuspend(lockAtMostFor) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) {
                "RedissonSuspend group extend failed. semaphore=${semaphore.name}, permitId=$permitId"
            }
            updateActiveOnError(e)
            ExtendOutcome.BackendError(e)
        }

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
     * 로컬 [active] 플래그 직접 조회 — Redisson 의 permitId 단위 조회 API 부재에 대한 우회.
     *
     * Diagnostic 용도만 사용 — 강한 보장 필요 시 [extendSuspend] 결과 사용.
     */
    override fun isHeld(): Boolean = active.get()

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
