package io.bluetape4k.leader

import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.identity.LeaderElectorBridgeLog
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Semaphore 기반 복수 리더 비동기 선출 계약을 정의합니다.
 *
 * ## [LeaderGroupElector] 과의 관계
 * - [LeaderGroupElector]은 [AsyncLeaderGroupElector]을 상속하며, 동기 [LeaderGroupElector.runIfLeader]를 추가합니다.
 * - [AsyncLeaderGroupElector]은 비동기 실행([runAsyncIfLeader])만 정의합니다.
 *
 * ## [AsyncLeaderElector] 과의 차이
 * - [AsyncLeaderElector]은 `lockName`당 리더를 1개로 제한합니다.
 * - [AsyncLeaderGroupElector]은 [maxLeaders]개까지 동시에 리더를 허용합니다.
 *
 * ## 동작/계약
 * - 구현체는 `lockName` 기준으로 최대 [maxLeaders]개의 `action`을 동시에 실행합니다.
 * - 슬롯이 가득 찬 경우, 빈 슬롯이 생길 때까지 [Executor] 스레드가 블로킹됩니다.
 * - `action` 예외 발생 시에도 슬롯이 반드시 반환됩니다.
 * - 상태 조회 메서드([state], [activeCount], [availableSlots])는 [LeaderGroupElectionState]에서 상속합니다.
 *
 * ```kotlin
 * val election: AsyncLeaderGroupElector = LocalAsyncLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runAsyncIfLeader("batch-job") {
 *     CompletableFuture.completedFuture(processChunk())
 * }.join()
 * ```
 */
interface AsyncLeaderGroupElector: LeaderGroupElectionState {

    /**
     * 슬롯을 획득하여 리더로 선출되면 비동기 [action]을 실행합니다.
     *
     * ## 동작/계약
     * - 슬롯이 가득 찬 경우 빈 슬롯이 생길 때까지 [executor] 스레드가 블로킹됩니다.
     * - [action]이 반환하는 [CompletableFuture]가 완료될 때까지 슬롯을 보유합니다.
     * - [action] 실패(예외 또는 future 실패) 시에도 슬롯은 반드시 반환됩니다.
     * - 기본 [executor]는 [VirtualThreadExecutor] 싱글턴으로, 블로킹 작업에 적합합니다.
     *
     * ```kotlin
     * val result = election.runAsyncIfLeader("job-lock") {
     *     CompletableFuture.completedFuture(42)
     * }.join()
     * // result == 42 (슬롯 획득 성공) 또는 null (획득 실패)
     * ```
     *
     * @param lockName 리더 그룹 선출에 사용할 락 이름
     * @param executor 비동기 실행에 사용할 [Executor]. 기본값은 [VirtualThreadExecutor] 싱글턴
     * @param action 리더 선출 성공 시 실행할 비동기 작업
     * @return [action] 실행 결과를 담은 [CompletableFuture]. 슬롯 획득 실패 시 `null`로 완료됨
     */
    fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor = VirtualThreadExecutor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?>

    /**
     * Runs [action] asynchronously if a group slot is acquired, stamping [slot.leaderId] as audit identity.
     *
     * ## Bridge Default
     * Delegates to [runAsyncIfLeader] (lockName-based) and emits a throttled WARN via
     * [LeaderElectorBridgeLog]. Backend implementations MUST override to carry [slot.leaderId]
     * into the lease/lock audit identity.
     *
     * @param slot the [LeaderSlot] carrying both lock name and audit leader id.
     * @param executor the [Executor] for async execution. Defaults to [VirtualThreadExecutor].
     * @param action the async action to run when a slot is acquired.
     * @return [CompletableFuture] resolving to the action result, or `null` when no slot acquired.
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
     * Returns [LeaderRunResult] for the async group slot election.
     *
     * ## Bridge Default
     * Uses an `elected: AtomicBoolean` flag pattern to distinguish elected (action ran) from skipped.
     * Returns `Elected(value, leaderId = null)` — fabrication of [slot.leaderId] is intentionally
     * blocked. Backend MUST override BOTH slot variants to carry [slot.leaderId] through.
     *
     * @param slot the [LeaderSlot] carrying both lock name and audit leader id.
     * @param executor the [Executor] for async execution. Defaults to [VirtualThreadExecutor].
     * @param action the async action to run when elected.
     * @return [CompletableFuture] resolving to [LeaderRunResult.Elected] or [LeaderRunResult.Skipped].
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
        }.handle { value, failure ->
            when {
                failure != null && elected.get() -> failure.toActionFailedResult()
                failure != null -> throw failure.asCompletionException()
                elected.get() -> LeaderRunResult.Elected(value)
                else -> LeaderRunResult.Skipped
            }
        }
    }

    private fun Throwable.unwrapCompletionCause(): Throwable =
        (this as? CompletionException)?.cause ?: this

    private fun Throwable.toActionFailedResult(): LeaderRunResult.ActionFailed {
        val cause = unwrapCompletionCause()
        if (cause is CancellationException) {
            throw cause
        }
        return LeaderRunResult.ActionFailed(cause)
    }

    private fun Throwable.asCompletionException(): CompletionException =
        this as? CompletionException ?: CompletionException(this)
}
