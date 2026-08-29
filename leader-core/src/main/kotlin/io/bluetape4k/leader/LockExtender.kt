package io.bluetape4k.leader

import io.bluetape4k.leader.coroutines.LockHandleElement
import io.bluetape4k.leader.internal.LockStateHolder
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

/**
 * `LockExtender` 선언은 leader election 계약에서 사용되는 object입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
object LockExtender : KLogging() {

    /**
     * `extendActiveLock` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockAtMostFor `lockAtMostFor` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    @JvmStatic
    fun extendActiveLock(lockAtMostFor: Duration): Boolean {
        val outcome = extendActiveLockDetailed(lockAtMostFor)
        return processBooleanResult(outcome)
    }

    /**
     * `extendActiveLock` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @param lockAtMostFor `lockAtMostFor` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    @JvmStatic
    fun extendActiveLock(lockName: String, lockAtMostFor: Duration): Boolean {
        val outcome = extendActiveLockDetailed(lockName, lockAtMostFor)
        return processBooleanResult(outcome)
    }

    /**
     * `extendActiveLock` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockAtMostFor `lockAtMostFor` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    @JvmStatic
    fun extendActiveLock(lockAtMostFor: java.time.Duration): Boolean =
        extendActiveLock(lockAtMostFor.toKotlinDuration())

    /**
     * `extendActiveLock` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @param lockAtMostFor `lockAtMostFor` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    @JvmStatic
    fun extendActiveLock(lockName: String, lockAtMostFor: java.time.Duration): Boolean =
        extendActiveLock(lockName, lockAtMostFor.toKotlinDuration())

    /**
     * `extendActiveLockDetailed` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockAtMostFor `lockAtMostFor` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    @JvmStatic
    fun extendActiveLockDetailed(lockAtMostFor: Duration): ExtendOutcome {
        val handle = LockStateHolder.peekSync()
            ?: return outsideScope(LeaderLeaseExtensionExecution.BLOCKING)
        return extendDetailedInternal(handle, lockAtMostFor)
    }

    /**
     * `extendActiveLockDetailed` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @param lockAtMostFor `lockAtMostFor` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    @JvmStatic
    fun extendActiveLockDetailed(lockName: String, lockAtMostFor: Duration): ExtendOutcome {
        val handle = LockStateHolder.peekSyncMatching(lockName)
            ?: return outsideScope(LeaderLeaseExtensionExecution.BLOCKING)
        return extendDetailedInternal(handle, lockAtMostFor)
    }

    /**
     * `extendActiveLockSuspend` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockAtMostFor `lockAtMostFor` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    suspend fun extendActiveLockSuspend(lockAtMostFor: Duration): Boolean {
        val outcome = extendActiveLockDetailedSuspend(lockAtMostFor)
        return processBooleanResult(outcome)
    }

    /**
     * `extendActiveLockSuspend` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @param lockAtMostFor `lockAtMostFor` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    suspend fun extendActiveLockSuspend(lockName: String, lockAtMostFor: Duration): Boolean {
        val outcome = extendActiveLockDetailedSuspend(lockName, lockAtMostFor)
        return processBooleanResult(outcome)
    }

    /**
     * `extendActiveLockDetailedSuspend` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockAtMostFor `lockAtMostFor` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    suspend fun extendActiveLockDetailedSuspend(lockAtMostFor: Duration): ExtendOutcome {
        val handle = coroutineContext[LockHandleElement]?.handle
            ?: return outsideScope(LeaderLeaseExtensionExecution.SUSPEND)
        return extendDetailedSuspendInternal(handle, lockAtMostFor)
    }

    /**
     * `extendActiveLockDetailedSuspend` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @param lockAtMostFor `lockAtMostFor` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    suspend fun extendActiveLockDetailedSuspend(lockName: String, lockAtMostFor: Duration): ExtendOutcome {
        val handle = coroutineContext[LockHandleElement]?.handle
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
     * `processBooleanResult` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param outcome `outcome` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
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
