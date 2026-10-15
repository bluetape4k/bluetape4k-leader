package io.bluetape4k.leader

import io.bluetape4k.leader.coroutines.LockHandleElement
import io.bluetape4k.leader.internal.LockStateHolder
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

/**
 * blocking 또는 coroutine 실행 문맥에 연결된 lock handle을 통해 backend lease 연장을 요청하는 facade입니다.
 *
 */
object LockExtender: KLogging() {

    /**
     * 현재 실행 문맥에 캡처된 lock의 lease 연장을 시도합니다. 반환값은 연장 성공 여부만 나타냅니다.
     * @param lockAtMostFor 연장 성공 후 유지할 lease TTL입니다.
     * @return 연장 성공이면 `true`, 그렇지 않으면 `false`입니다.
     */
    @JvmStatic
    fun extendActiveLock(lockAtMostFor: Duration): Boolean {
        val outcome = extendActiveLockDetailed(lockAtMostFor)
        return processBooleanResult(outcome)
    }

    /**
     * 현재 실행 문맥에 캡처된 lock의 lease 연장을 시도합니다. 반환값은 연장 성공 여부만 나타냅니다.
     * @param lockName 현재 실행 문맥에서 연장할 lock 이름입니다. 지정하면 캡처된 handle의 이름과 일치해야 합니다.
     * @param lockAtMostFor 연장 성공 후 유지할 lease TTL입니다.
     * @return 연장 성공이면 `true`, 그렇지 않으면 `false`입니다.
     */
    @JvmStatic
    fun extendActiveLock(lockName: String, lockAtMostFor: Duration): Boolean {
        val outcome = extendActiveLockDetailed(lockName, lockAtMostFor)
        return processBooleanResult(outcome)
    }

    /**
     * 현재 실행 문맥에 캡처된 lock의 lease 연장을 시도합니다. 반환값은 연장 성공 여부만 나타냅니다.
     * @param lockAtMostFor 연장 성공 후 유지할 lease TTL입니다.
     * @return 연장 성공이면 `true`, 그렇지 않으면 `false`입니다.
     */
    @JvmStatic
    fun extendActiveLock(lockAtMostFor: java.time.Duration): Boolean =
        extendActiveLock(lockAtMostFor.toKotlinDuration())

    /**
     * 현재 실행 문맥에 캡처된 lock의 lease 연장을 시도합니다. 반환값은 연장 성공 여부만 나타냅니다.
     * @param lockName 현재 실행 문맥에서 연장할 lock 이름입니다. 지정하면 캡처된 handle의 이름과 일치해야 합니다.
     * @param lockAtMostFor 연장 성공 후 유지할 lease TTL입니다.
     * @return 연장 성공이면 `true`, 그렇지 않으면 `false`입니다.
     */
    @JvmStatic
    fun extendActiveLock(lockName: String, lockAtMostFor: java.time.Duration): Boolean =
        extendActiveLock(lockName, lockAtMostFor.toKotlinDuration())

    /**
     * 현재 실행 문맥에 캡처된 lock의 lease 연장을 시도합니다. 연장 결과를 `ExtendOutcome`으로 구분해 반환합니다.
     * @param lockAtMostFor 연장 성공 후 유지할 lease TTL입니다.
     * @return 성공, 미보유, thread 제약, 큐 거부 또는 backend 오류를 구분하는 결과입니다.
     */
    @JvmStatic
    fun extendActiveLockDetailed(lockAtMostFor: Duration): ExtendOutcome {
        val handle = LockStateHolder.peekSync()
            ?: return outsideScope(LeaderLeaseExtensionExecution.BLOCKING)
        return extendDetailedInternal(handle, lockAtMostFor)
    }

    /**
     * 현재 실행 문맥에 캡처된 lock의 lease 연장을 시도합니다. 연장 결과를 `ExtendOutcome`으로 구분해 반환합니다.
     * @param lockName 현재 실행 문맥에서 연장할 lock 이름입니다. 지정하면 캡처된 handle의 이름과 일치해야 합니다.
     * @param lockAtMostFor 연장 성공 후 유지할 lease TTL입니다.
     * @return 성공, 미보유, thread 제약, 큐 거부 또는 backend 오류를 구분하는 결과입니다.
     */
    @JvmStatic
    fun extendActiveLockDetailed(lockName: String, lockAtMostFor: Duration): ExtendOutcome {
        val handle = LockStateHolder.peekSyncMatching(lockName)
            ?: return outsideScope(LeaderLeaseExtensionExecution.BLOCKING)
        return extendDetailedInternal(handle, lockAtMostFor)
    }

    /**
     * coroutine context에 캡처된 lock의 lease 연장을 시도합니다. 반환값은 연장 성공 여부만 나타냅니다.
     * @param lockAtMostFor 연장 성공 후 유지할 lease TTL입니다.
     * @return 연장 성공이면 `true`, 그렇지 않으면 `false`입니다.
     */
    suspend fun extendActiveLockSuspend(lockAtMostFor: Duration): Boolean {
        val outcome = extendActiveLockDetailedSuspend(lockAtMostFor)
        return processBooleanResult(outcome)
    }

    /**
     * coroutine context에 캡처된 lock의 lease 연장을 시도합니다. 반환값은 연장 성공 여부만 나타냅니다.
     * @param lockName 현재 실행 문맥에서 연장할 lock 이름입니다. 지정하면 캡처된 handle의 이름과 일치해야 합니다.
     * @param lockAtMostFor 연장 성공 후 유지할 lease TTL입니다.
     * @return 연장 성공이면 `true`, 그렇지 않으면 `false`입니다.
     */
    suspend fun extendActiveLockSuspend(lockName: String, lockAtMostFor: Duration): Boolean {
        val outcome = extendActiveLockDetailedSuspend(lockName, lockAtMostFor)
        return processBooleanResult(outcome)
    }

    /**
     * coroutine context에 캡처된 lock의 lease 연장을 시도합니다. 연장 결과를 `ExtendOutcome`으로 구분해 반환합니다.
     * @param lockAtMostFor 연장 성공 후 유지할 lease TTL입니다.
     * @return 성공, 미보유, thread 제약, 큐 거부 또는 backend 오류를 구분하는 결과입니다.
     */
    suspend fun extendActiveLockDetailedSuspend(lockAtMostFor: Duration): ExtendOutcome {
        val handle = coroutineContext[LockHandleElement]?.handle
            ?: return outsideScope(LeaderLeaseExtensionExecution.SUSPEND)
        return extendDetailedSuspendInternal(handle, lockAtMostFor)
    }

    /**
     * coroutine context에 캡처된 lock의 lease 연장을 시도합니다. 연장 결과를 `ExtendOutcome`으로 구분해 반환합니다.
     * @param lockName 현재 실행 문맥에서 연장할 lock 이름입니다. 지정하면 캡처된 handle의 이름과 일치해야 합니다.
     * @param lockAtMostFor 연장 성공 후 유지할 lease TTL입니다.
     * @return 성공, 미보유, thread 제약, 큐 거부 또는 backend 오류를 구분하는 결과입니다.
     */
    suspend fun extendActiveLockDetailedSuspend(lockName: String, lockAtMostFor: Duration): ExtendOutcome {
        val handle = currentCoroutineContext()[LockHandleElement]?.handle
        if (handle == null || handle.lockName != lockName) {
            return outsideScope(LeaderLeaseExtensionExecution.SUSPEND)
        }
        return extendDetailedSuspendInternal(handle, lockAtMostFor)
    }

    // --- internal helpers ---

    @Suppress("TooGenericExceptionCaught")
    private fun extendDetailedInternal(handle: LeaderLockHandle, lockAtMostFor: Duration): ExtendOutcome {
        val observationScope = LeaderLeaseExtensionObservationScope.currentOrNull()
        val observing = LeaderLeaseExtensionObservers.hasObservers(observationScope)
        val context = if (observing) extensionContext(handle) else null

        if (handle is LeaderLockHandle.FailOpen) {
            log.warn { "LockExtender — current scope is fail-open sentinel (lockName=${handle.lockName})" }
            val outcome = ExtendOutcome.NotHeld
            publishUserEvent(
                observing,
                observationScope,
                LeaderLeaseExtensionExecution.BLOCKING,
                outcome,
                context,
                elapsedNanos = 0L,
            )
            return outcome
        }
        val real = handle as LeaderLockHandle.Real

        // backend extend 성공 후에만 갱신합니다. backend가 관측한 만료 시각을 사용해 watchdog skip이
        // 실제로 갱신된 lease보다 오래 유지되지 않게 합니다.
        val delegateStartedAtNanos = if (observing) System.nanoTime() else 0L
        return try {
            val outcome = real.extend(lockAtMostFor)
            val delegateElapsedNanos = elapsedNanos(delegateStartedAtNanos, observing)
            if (outcome is ExtendOutcome.Extended) {
                real.extendDelegate.lastExtendDeadline.set(outcome.observedExpireAt)
            }
            publishUserEvent(
                observing,
                observationScope,
                LeaderLeaseExtensionExecution.BLOCKING,
                outcome,
                context,
                elapsedNanos = delegateElapsedNanos,
            )
            outcome
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            publishUserEvent(
                observing,
                observationScope,
                LeaderLeaseExtensionExecution.BLOCKING,
                ExtendOutcome.BackendError(ex),
                context,
                elapsedNanos = elapsedNanos(delegateStartedAtNanos, observing),
            )
            throw ex
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun extendDetailedSuspendInternal(
        handle: LeaderLockHandle,
        lockAtMostFor: Duration,
    ): ExtendOutcome {
        val observationScope = LeaderLeaseExtensionObservationScope.currentOrNull()
        val observing = LeaderLeaseExtensionObservers.hasObservers(observationScope)
        val context = if (observing) extensionContext(handle) else null

        if (handle is LeaderLockHandle.FailOpen) {
            log.warn { "LockExtender — current scope is fail-open sentinel (lockName=${handle.lockName})" }
            val outcome = ExtendOutcome.NotHeld
            publishUserEvent(
                observing,
                observationScope,
                LeaderLeaseExtensionExecution.SUSPEND,
                outcome,
                context,
                elapsedNanos = 0L,
            )
            return outcome
        }
        val real = handle as LeaderLockHandle.Real

        // backend extend 성공 후에만 갱신합니다. backend가 관측한 만료 시각을 사용해 watchdog skip이
        // 실제로 갱신된 lease보다 오래 유지되지 않게 합니다.
        val delegateStartedAtNanos = if (observing) System.nanoTime() else 0L
        return try {
            val outcome = real.extendSuspend(lockAtMostFor)
            val delegateElapsedNanos = elapsedNanos(delegateStartedAtNanos, observing)
            if (outcome is ExtendOutcome.Extended) {
                real.extendDelegate.lastExtendDeadline.set(outcome.observedExpireAt)
            }
            publishUserEvent(
                observing,
                observationScope,
                LeaderLeaseExtensionExecution.SUSPEND,
                outcome,
                context,
                elapsedNanos = delegateElapsedNanos,
            )
            outcome
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            publishUserEvent(
                observing,
                observationScope,
                LeaderLeaseExtensionExecution.SUSPEND,
                ExtendOutcome.BackendError(ex),
                context,
                elapsedNanos = elapsedNanos(delegateStartedAtNanos, observing),
            )
            throw ex
        }
    }

    private fun elapsedNanos(startedAtNanos: Long, observing: Boolean): Long =
        if (observing) (System.nanoTime() - startedAtNanos).coerceAtLeast(0L) else 0L

    private fun outsideScope(execution: LeaderLeaseExtensionExecution): ExtendOutcome {
        log.warn { "LockExtender called outside an active @LeaderElection scope — returning NotHeld" }
        val outcome = ExtendOutcome.NotHeld
        val observationScope = LeaderLeaseExtensionObservationScope.currentOrNull()
        if (LeaderLeaseExtensionObservers.hasObservers(observationScope)) {
            LeaderLeaseExtensionObservers.publish(
                LeaderLeaseExtensionEvent(
                    source = LeaderLeaseExtensionSource.USER,
                    execution = execution,
                    outcome = outcome,
                    elapsedNanos = 0L,
                    context = null,
                ),
                observationScope,
            )
        }
        return outcome
    }

    private fun extensionContext(handle: LeaderLockHandle): LeaderLeaseExtensionContext = when (handle) {
        is LeaderLockHandle.Real -> LeaderLeaseExtensionContext(handle.lockName, handle.auditLeaderId)
        is LeaderLockHandle.FailOpen -> LeaderLeaseExtensionContext(handle.lockName, null)
    }

    private fun publishUserEvent(
        observing: Boolean,
        observationScope: LeaderLeaseExtensionObservationScope?,
        execution: LeaderLeaseExtensionExecution,
        outcome: ExtendOutcome,
        context: LeaderLeaseExtensionContext?,
        elapsedNanos: Long,
    ) {
        if (!observing) return
        LeaderLeaseExtensionObservers.publish(
            LeaderLeaseExtensionEvent(
                source = LeaderLeaseExtensionSource.USER,
                execution = execution,
                outcome = outcome,
                elapsedNanos = elapsedNanos,
                context = context,
            ),
            observationScope,
        )
    }

    /**
     * 상세 연장 결과를 기존 Boolean 계약으로 변환합니다. `Extended`만 `true`이고 나머지는 `false`입니다.
     * @param outcome backend 연장 결과 또는 연장을 시도할 수 없었던 원인입니다.
     */
    private fun processBooleanResult(outcome: ExtendOutcome): Boolean = when (outcome) {
        is ExtendOutcome.Extended -> true
        is ExtendOutcome.NotHeld -> {
            // backend-origin NotHeld: token mismatch, takeover, lease expired를 포함합니다.
            // (outsideScope / FailOpen path 에서 온 NotHeld 는 path-specific WARN 이미 발생 — double log)
            log.warn { "LockExtender — extend returned NotHeld (token mismatch / takeover / lease expired / scope absent)" }
            false
        }
        is ExtendOutcome.WrongThread -> {
            log.warn { "LockExtender — extend failed: WrongThread (Redisson thread-bound lock called from wrong thread)" }
            false
        }
        is ExtendOutcome.Rejected -> {
            log.warn { "LockExtender — extend rejected by the bounded operation queue" }
            false
        }
        is ExtendOutcome.BackendError -> {
            log.warn(outcome.cause) {
                "LockExtender — backend error during extend " +
                        "(use extendActiveLockDetailed + BackendErrorClassifier for non-transient classification)"
            }
            false
        }
    }
}
