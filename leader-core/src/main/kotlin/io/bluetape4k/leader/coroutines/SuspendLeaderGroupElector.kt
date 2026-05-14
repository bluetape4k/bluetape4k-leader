package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderGroupElectionState
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.identity.LeaderElectorBridgeLog
import kotlin.coroutines.cancellation.CancellationException

/**
 * 코루틴 기반 복수 리더 선출 계약을 정의합니다.
 *
 * ## [SuspendLeaderElector] 과의 차이
 * - [SuspendLeaderElector]은 `lockName`당 리더를 1개로 제한합니다.
 * - [SuspendLeaderGroupElector]은 [maxLeaders]개까지 동시에 리더를 허용합니다.
 * - 내부적으로 `kotlinx.coroutines.sync.Semaphore(maxLeaders)`를 사용합니다.
 *
 * ## [LeaderGroupElectionState] 상속
 * - [maxLeaders], [activeCount], [availableSlots], [state] 상태 조회 메서드를 공유합니다.
 *
 * ## 동작/계약
 * - 구현체는 `lockName` 기준으로 최대 [maxLeaders]개의 `action`을 동시에 실행합니다.
 * - 슬롯이 가득 찬 경우 [waitTime] 내 슬롯을 획득하지 못하면 `null`을 반환합니다 (ShedLock skip 방식).
 * - `action` 예외 발생 시에도 슬롯이 반드시 반환됩니다.
 * - 코루틴 취소 시 슬롯은 반드시 반환되어야 하며, `CancellationException`은 반환 작업 후 재전파해야 합니다.
 * - 상태 조회 메서드([state], [activeCount], [availableSlots])는 근사값을 반환할 수 있습니다.
 *
 * ```kotlin
 * val election = LocalSuspendLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runIfLeader("batch-job") { processChunkSuspend() }
 *
 * println(election.state("batch-job"))  // LeaderGroupState(activeCount=2, ...)
 * ```
 */
interface SuspendLeaderGroupElector: LeaderGroupElectionState {

    /**
     * 슬롯을 획득하여 리더로 선출되면 suspend [action]을 실행합니다.
     *
     * ## 동작/계약
     * - 슬롯이 가득 찬 경우 빈 슬롯이 생길 때까지 코루틴이 suspend됩니다.
     * - [action] 예외 발생 시에도 슬롯은 반드시 반환됩니다.
     * - [action] 실행 중 [activeCount]가 증가하고, 완료 시 감소합니다.
     *
     * ```kotlin
     * val result = election.runIfLeader("job-lock") { computeSuspend() }
     * // result == computeSuspend() 반환값 (슬롯 획득 성공) 또는 null (획득 실패)
     * ```
     *
     * @param lockName 리더 그룹 선출에 사용할 락 이름
     * @param action 리더 선출 성공 시 실행할 suspend 작업
     * @return [action] 실행 결과, 슬롯 획득 실패 시 `null`
     */
    suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T?

    /**
     * 리더 선출 결과를 명시적으로 표현하는 결과형 API.
     *
     * [runIfLeader] 가 `null` 을 반환할 때 (a) 슬롯 미획득 vs (b) action 이 null 반환 모호함을 제거.
     *
     * ## 동작/계약
     * - 슬롯 획득 성공 → [LeaderRunResult.Elected]`(value)` — `value` 는 action 반환값 (null 가능)
     * - 슬롯 미획득 → [LeaderRunResult.Skipped]
     * - action 실행 실패 → [LeaderRunResult.ActionFailed]
     * - `CancellationException` 은 [LeaderRunResult.ActionFailed] 로 감싸지 않고 재전파
     * - `elected: Boolean` flag 패턴으로 정확 분류 (action 이 null 반환해도 [LeaderRunResult.Elected])
     *
     * ## binary-compat (Step 2-R R3-F3)
     * Kotlin interface default fun 으로 추가 — `-jvm-default=enable` 빌드 하에서 JVM `default` method 로 컴파일,
     * 기존 외부 구현체 binary 호환 보존.
     *
     * ```kotlin
     * val result = election.runIfLeaderResultSuspend("batch-job") { processChunkSuspend() }
     * when (result) {
     *     is LeaderRunResult.Elected -> println("elected, value=${result.value}")
     *     LeaderRunResult.Skipped   -> println("slot full — skipped")
     *     is LeaderRunResult.ActionFailed -> println("action failed: ${result.cause.message}")
     * }
     * ```
     *
     * @param lockName 리더 그룹 선출에 사용할 락 이름
     * @param action 슬롯 획득 성공 시 실행할 suspend 작업
     * @return [LeaderRunResult.Elected] (action 실행됨) 또는 [LeaderRunResult.Skipped] (슬롯 미획득)
     */
    suspend fun <T> runIfLeaderResultSuspend(
        lockName: String,
        action: suspend () -> T,
    ): LeaderRunResult<T> {
        var elected = false
        val value = try {
            runIfLeader(lockName) {
                elected = true
                action()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (elected) {
                return LeaderRunResult.ActionFailed(e)
            }
            throw e
        }
        return if (elected) LeaderRunResult.Elected(value) else LeaderRunResult.Skipped
    }

    /**
     * Runs [action] if a group slot is acquired, stamping [slot.leaderId] as audit identity.
     *
     * ## Bridge Default
     * Delegates to [runIfLeader] (lockName-based) and emits a throttled WARN via
     * [LeaderElectorBridgeLog]. Backend implementations MUST override to stamp [slot.leaderId]
     * into [LeaderLease.auditLeaderId] for audit traceability.
     *
     * @param slot the [LeaderSlot] carrying both lock name and audit leader id.
     * @param action the suspend action to run when a slot is acquired.
     * @return [action] result, or `null` when no slot acquired.
     */
    suspend fun <T> runIfLeader(slot: LeaderSlot, action: suspend () -> T): T? {
        LeaderElectorBridgeLog.global().warnOnBridgeUse(this::class, slot)
        return runIfLeader(slot.lockName, action)
    }

    /**
     * Returns [LeaderRunResult] for this suspend group slot election.
     *
     * ## Bridge Default
     * Returns `Elected(value, leaderId = null)` — fabrication of [slot.leaderId] is intentionally
     * blocked. Backend MUST override BOTH slot variants to carry [slot.leaderId] through.
     *
     * Cancellation: the underlying [runIfLeader] propagates `CancellationException` directly;
     * no `runCatching` is used around suspend calls.
     *
     * @param slot the [LeaderSlot] carrying both lock name and audit leader id.
     * @param action the suspend action to run when a slot is acquired.
     * @return [LeaderRunResult.Elected] (action ran) or [LeaderRunResult.Skipped] (no slot acquired).
     */
    suspend fun <T> runIfLeaderResultSuspend(
        slot: LeaderSlot,
        action: suspend () -> T,
    ): LeaderRunResult<T> {
        LeaderElectorBridgeLog.global().warnOnResultBridgeUse(this::class, slot)
        var elected = false
        val value = try {
            runIfLeader(slot.lockName) {
                elected = true
                action()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (elected) {
                return LeaderRunResult.ActionFailed(e)
            }
            throw e
        }
        return if (elected) LeaderRunResult.Elected(value) else LeaderRunResult.Skipped
    }
}
