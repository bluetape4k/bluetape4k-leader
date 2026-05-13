package io.bluetape4k.leader

import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.identity.LeaderElectorBridgeLog
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 비동기 리더 선출 실행 계약을 정의합니다.
 *
 * ## 동작/계약
 * - 구현체는 `lockName` 기준으로 리더 획득 성공 시에만 `action`을 실행합니다.
 * - 반환 [CompletableFuture] 완료 시점은 리더 획득 및 `action` 완료 시점에 의존합니다.
 * - 리더 선출 실패/실행 오류의 예외 모델은 구현체 정책을 따릅니다.
 * - 기본 [Executor]는 [VirtualThreadExecutor] 싱글턴을 사용합니다.
 *   Virtual Thread는 락 대기·future 블로킹 시 carrier thread를 반납하므로 비동기 선출에 적합합니다.
 *   Java 21 이상 환경을 전제합니다.
 *
 * ```kotlin
 * val future = election.runAsyncIfLeader("batch-lock") { CompletableFuture.completedFuture("ok") }
 * // future.join() == "ok"
 * ```
 */
interface AsyncLeaderElector: LeaderElectionState {

    /**
     * 리더 획득 성공 시 비동기 [action]을 실행합니다.
     *
     * ## 동작/계약
     * - [executor]는 리더 선출/작업 실행 경로에서 구현체가 사용할 수 있습니다.
     * - [action]이 반환한 future의 성공/실패 상태가 결과 future로 반영됩니다.
     * - [lockName] 검증 규칙은 구현체 정책을 따릅니다.
     * - 기본 [executor]는 [VirtualThreadExecutor] 싱글턴으로, 블로킹 작업에 적합합니다.
     *
     * ```kotlin
     * val result = election.runAsyncIfLeader("job", action = { CompletableFuture.completedFuture(1) }).join()
     * // result == 1 (리더 획득 성공) 또는 null (획득 실패)
     * ```
     *
     * @param lockName 리더 선출에 사용할 락 이름
     * @param executor 비동기 실행에 사용할 [Executor]. 기본값은 [VirtualThreadExecutor] 싱글턴
     * @param action 리더 획득 성공 시 실행할 비동기 작업
     * @return 리더 실행 결과를 담은 [CompletableFuture]. 리더 획득 실패 시 `null`로 완료됨
     */
    fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor = VirtualThreadExecutor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?>

    /**
     * Runs [action] asynchronously if elected for [slot], stamping [slot.leaderId] as audit identity.
     *
     * ## Bridge Default
     * This default delegates to [runAsyncIfLeader] (lockName-based) and emits a throttled WARN via
     * [LeaderElectorBridgeLog] on first use per `(implClass, slot)` pair.
     * Backend implementations MUST override this method to stamp [slot.leaderId] into
     * [LeaderLease.auditLeaderId] for audit traceability.
     *
     * @param slot the [LeaderSlot] carrying both lock name and audit leader id.
     * @param executor the [Executor] for async execution. Defaults to [VirtualThreadExecutor].
     * @param action the async action to run when elected.
     * @return [CompletableFuture] resolving to the action result, or `null` when not elected.
     */
    fun <T> runAsyncIfLeader(
        slot: LeaderSlot,
        executor: Executor = VirtualThreadExecutor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        LeaderElectorBridgeLog.global().warnOnBridgeUse(this::class, slot)
        return runAsyncIfLeader(slot.lockName, executor, action)
    }

    /**
     * Returns [LeaderRunResult] for the async slot election.
     *
     * ## Bridge Default
     * Uses an `elected: AtomicBoolean` flag pattern to distinguish elected (action ran) from skipped.
     * Returns `Elected(value, leaderId = null)` — fabrication of [slot.leaderId] is intentionally
     * blocked because the bridge cannot verify that the backend actually used this slot's identity.
     *
     * Backend implementations MUST override BOTH slot variants
     * ([runAsyncIfLeader] and [runAsyncIfLeaderResult]) to carry [slot.leaderId] through to
     * [LeaderRunResult.Elected.leaderId].
     *
     * @param slot the [LeaderSlot] carrying both lock name and audit leader id.
     * @param executor the [Executor] for async execution. Defaults to [VirtualThreadExecutor].
     * @param action the async action to run when elected.
     * @return [CompletableFuture] resolving to [LeaderRunResult.Elected] (action ran) or
     *   [LeaderRunResult.Skipped] (not elected).
     */
    fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        executor: Executor = VirtualThreadExecutor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<LeaderRunResult<T>> {
        LeaderElectorBridgeLog.global().warnOnResultBridgeUse(this::class, slot)
        val elected = AtomicBoolean(false)
        return runAsyncIfLeader(slot.lockName, executor) {
            elected.set(true)
            action()
        }.thenApply { value ->
            if (elected.get()) LeaderRunResult.Elected(value) else LeaderRunResult.Skipped
        }
    }
}
