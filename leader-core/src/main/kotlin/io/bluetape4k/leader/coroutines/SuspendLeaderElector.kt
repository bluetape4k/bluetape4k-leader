package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderElectionState
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.identity.LeaderElectorBridgeLog

/**
 * 코루틴 기반 리더 선출 실행 계약을 정의합니다.
 *
 * ## 동작/계약
 * - 구현체는 동일 [lockName]에 대해 리더 획득 성공 호출만 [action]을 실행합니다.
 * - [action]은 suspend 함수이며 호출 컨텍스트/디스패처는 구현체 정책을 따릅니다.
 * - 리더 획득 실패 시 `null`을 반환합니다 (ShedLock skip 방식).
 * - [action] 실행 중 코루틴 취소 시 락/슬롯은 반드시 반환되어야 하며,
 *   `CancellationException`은 반환 작업 후 호출자에게 재전파해야 합니다.
 *
 * ```kotlin
 * val result = election.runIfLeader("sync-job") { "ok" }
 * // result == "ok"
 * ```
 */
interface SuspendLeaderElector: LeaderElectionState {

    /**
     * 리더 획득 성공 시 suspend [action]을 실행합니다.
     *
     * ## 동작/계약
     * - [lockName] 기준 리더 획득 성공 시 [action]을 1회 실행합니다.
     * - [action] 예외는 호출자에게 전파됩니다.
     * - [lockName] 검증 규칙은 구현체 정책을 따릅니다.
     *
     * ```kotlin
     * val value = election.runIfLeader("job-lock") { 7 }
     * // value == 7 (리더 획득 성공) 또는 null (획득 실패)
     * ```
     *
     * @param lockName 리더 선출에 사용할 락 이름
     * @param action 리더 획득 성공 시 실행할 suspend 작업
     * @return [action] 실행 결과, 리더 획득 실패 시 `null`
     */
    suspend fun <T> runIfLeader(
        lockName: String,
        action: suspend () -> T,
    ): T?

    /**
     * 리더 선출 결과를 명시적으로 표현하는 결과형 API.
     *
     * [runIfLeader] 가 `null` 을 반환할 때 (a) 리더 미선출 vs (b) action 이 null 반환 모호함을 제거.
     *
     * ## 동작/계약
     * - 리더 선출 성공 → [LeaderRunResult.Elected]`(value)` — `value` 는 action 반환값 (null 가능)
     * - 리더 미선출 → [LeaderRunResult.Skipped]
     * - `elected: Boolean` flag 패턴으로 정확 분류 (action 이 null 반환해도 [LeaderRunResult.Elected])
     *
     * ## binary-compat (Step 2-R R3-F3)
     * Kotlin interface default fun 으로 추가 — `-jvm-default=enable` 빌드 하에서 JVM `default` method 로 컴파일,
     * 기존 외부 구현체 binary 호환 보존.
     *
     * ```kotlin
     * val result = election.runIfLeaderResultSuspend("job-lock") { computeResult() }
     * when (result) {
     *     is LeaderRunResult.Elected -> println("elected, value=${result.value}")
     *     LeaderRunResult.Skipped   -> println("not elected")
     * }
     * ```
     *
     * @param lockName 리더 선출에 사용할 락 이름
     * @param action 리더 획득 성공 시 실행할 suspend 작업
     * @return [LeaderRunResult.Elected] (action 실행됨) 또는 [LeaderRunResult.Skipped] (리더 미선출)
     */
    suspend fun <T> runIfLeaderResultSuspend(
        lockName: String,
        action: suspend () -> T,
    ): LeaderRunResult<T> {
        var elected = false
        val value = runIfLeader(lockName) { elected = true; action() }
        return if (elected) LeaderRunResult.Elected(value) else LeaderRunResult.Skipped
    }

    /**
     * Runs [action] if elected for [slot], stamping [slot.leaderId] as audit identity.
     *
     * ## Bridge Default
     * Delegates to [runIfLeader] (lockName-based) and emits a throttled WARN via
     * [LeaderElectorBridgeLog]. Backend implementations MUST override to stamp [slot.leaderId]
     * into [LeaderLease.auditLeaderId] for audit traceability.
     *
     * @param slot the [LeaderSlot] carrying both lock name and audit leader id.
     * @param action the suspend action to run when elected.
     * @return [action] result, or `null` when not elected.
     */
    suspend fun <T> runIfLeader(slot: LeaderSlot, action: suspend () -> T): T? {
        LeaderElectorBridgeLog.global().warnOnBridgeUse(this::class, slot)
        return runIfLeader(slot.lockName, action)
    }

    /**
     * Returns [LeaderRunResult] for this suspend slot election.
     *
     * ## Bridge Default
     * Returns `Elected(value, leaderId = null)` — fabrication of [slot.leaderId] is intentionally
     * blocked. Backend MUST override BOTH slot variants to carry [slot.leaderId] through.
     *
     * Cancellation: the underlying [runIfLeader] propagates `CancellationException` directly;
     * no `runCatching` is used around suspend calls.
     *
     * @param slot the [LeaderSlot] carrying both lock name and audit leader id.
     * @param action the suspend action to run when elected.
     * @return [LeaderRunResult.Elected] (action ran) or [LeaderRunResult.Skipped] (not elected).
     */
    suspend fun <T> runIfLeaderResultSuspend(
        slot: LeaderSlot,
        action: suspend () -> T,
    ): LeaderRunResult<T> {
        LeaderElectorBridgeLog.global().warnOnResultBridgeUse(this::class, slot)
        var elected = false
        val value = runIfLeader(slot.lockName) {
            elected = true
            action()
        }
        return if (elected) LeaderRunResult.Elected(value) else LeaderRunResult.Skipped
    }
}
