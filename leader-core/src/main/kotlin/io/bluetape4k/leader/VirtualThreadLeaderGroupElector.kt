package io.bluetape4k.leader

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.leader.identity.LeaderElectorBridgeLog
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Virtual Thread 기반 복수 리더 비동기 선출 계약을 정의합니다.
 *
 * ## [VirtualThreadLeaderElector] 과의 차이
 * - [VirtualThreadLeaderElector]은 `lockName`당 리더를 1개로 제한합니다.
 * - [VirtualThreadLeaderGroupElector]은 [maxLeaders]개까지 동시에 리더를 허용합니다.
 *
 * ## [LeaderGroupElector] 과의 차이
 * - [LeaderGroupElector]의 [LeaderGroupElector.runAsyncIfLeader]는 [java.util.concurrent.CompletableFuture]를 반환합니다.
 * - 이 인터페이스의 [runAsyncIfLeader]는 [VirtualFuture]를 반환하며, `action`이 `() -> T` 람다로 단순합니다.
 * - Virtual Thread 기반이므로 I/O 블로킹 작업에 carrier thread를 소모하지 않습니다.
 *
 * ## 동작/계약
 * - 구현체는 `lockName` 기준으로 최대 [maxLeaders]개의 `action`을 동시에 실행합니다.
 * - 슬롯이 가득 찬 경우, 빈 슬롯이 생길 때까지 Virtual Thread가 블로킹됩니다.
 * - `action` 예외 발생 시에도 슬롯이 반드시 반환됩니다.
 * - 상태 조회 메서드([state], [activeCount], [availableSlots])는 [LeaderGroupElectionState]에서 상속합니다.
 *
 * ```kotlin
 * val election = LocalVirtualThreadLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runAsyncIfLeader("batch-job") { processChunk() }.await()
 * ```
 */
interface VirtualThreadLeaderGroupElector: LeaderGroupElectionState {

    /**
     * 슬롯을 획득하여 리더로 선출되면 [action]을 Virtual Thread에서 비동기로 실행합니다.
     *
     * ## 동작/계약
     * - 슬롯이 가득 찬 경우 빈 슬롯이 생길 때까지 Virtual Thread가 블로킹됩니다.
     * - [action] 예외 발생 시에도 슬롯은 반드시 반환됩니다.
     * - [VirtualFuture.await]로 결과를 동기 대기하거나 [VirtualFuture.toCompletableFuture]로 변환할 수 있습니다.
     *
     * ```kotlin
     * val result = election.runAsyncIfLeader("job-lock") { computeResult() }.await()
     * // result == computeResult() 반환값 (슬롯 획득 성공) 또는 null (획득 실패)
     * ```
     *
     * @param lockName 리더 그룹 선출에 사용할 락 이름
     * @param action 리더 선출 성공 시 실행할 작업. 결과를 직접 반환합니다.
     * @return [action] 실행 결과를 담은 [VirtualFuture]. 슬롯 획득 실패 시 `null`로 완료됨
     */
    fun <T> runAsyncIfLeader(
        lockName: String,
        action: () -> T,
    ): VirtualFuture<T?>

    /**
     * Runs [action] on a Virtual Thread if a group slot is acquired, stamping [slot.leaderId] as audit identity.
     *
     * ## Bridge Default
     * Delegates to [runAsyncIfLeader] (lockName-based) and emits a throttled WARN via
     * [LeaderElectorBridgeLog]. Backend implementations MUST override to stamp [slot.leaderId]
     * into [LeaderLease.auditLeaderId].
     *
     * @param slot the [LeaderSlot] carrying both lock name and audit leader id.
     * @param action the action to run when a slot is acquired.
     * @return [VirtualFuture] resolving to the action result, or `null` when no slot acquired.
     */
    fun <T> runAsyncIfLeader(
        slot: LeaderSlot,
        action: () -> T,
    ): VirtualFuture<T?> {
        LeaderElectorBridgeLog.global().warnOnBridgeUse(this::class, slot)
        return runAsyncIfLeader(slot.lockName, action)
    }

    /**
     * Returns [LeaderRunResult] for the Virtual Thread group slot election.
     *
     * ## Bridge Default
     * Uses an `elected: AtomicBoolean` flag pattern to distinguish elected (action ran) from skipped.
     * Returns `Elected(value, leaderId = null)` — fabrication of [slot.leaderId] is intentionally
     * blocked. Backend MUST override BOTH slot variants to carry [slot.leaderId] through.
     *
     * @param slot the [LeaderSlot] carrying both lock name and audit leader id.
     * @param action the action to run when elected.
     * @return [VirtualFuture] resolving to [LeaderRunResult.Elected] or [LeaderRunResult.Skipped].
     */
    fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        action: () -> T,
    ): VirtualFuture<LeaderRunResult<T>> {
        LeaderElectorBridgeLog.global().warnOnResultBridgeUse(this::class, slot)
        val elected = AtomicBoolean(false)
        val source: VirtualFuture<T?> = runAsyncIfLeader(slot.lockName) {
            elected.set(true)
            action()
        }
        val mapped: java.util.concurrent.CompletableFuture<LeaderRunResult<T>> =
            source.toCompletableFuture().handle { value, failure ->
                when {
                    failure != null && elected.get() -> failure.toActionFailedResult()
                    failure != null -> throw failure.asCompletionException()
                    elected.get() -> LeaderRunResult.Elected(value) as LeaderRunResult<T>
                    else -> LeaderRunResult.Skipped as LeaderRunResult<T>
                }
            }
        return VirtualFuture(mapped)
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
