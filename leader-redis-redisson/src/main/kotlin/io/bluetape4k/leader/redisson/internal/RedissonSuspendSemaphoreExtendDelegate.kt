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
 * `RedissonSuspendSemaphoreExtendDelegate`는 Redis Redisson backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property semaphore Redis Redisson backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property permitId Redis Redisson backend 호출과 상태 계산에 사용하는 속성입니다.
 */
internal class RedissonSuspendSemaphoreExtendDelegate(
    private val semaphore: RPermitExpirableSemaphore,
    private val permitId: String,
): SuspendExtendDelegate {

    companion object: KLoggingChannel()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    /**
     * `active` 값은 Redis Redisson backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
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
     * `isHeldSuspend` 호출은 Redis Redisson backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
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
