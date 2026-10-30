package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderElectionState
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.identity.LeaderElectorBridgeLog
import kotlin.coroutines.cancellation.CancellationException

/**
 * suspend 함수로 lock 획득을 시도하고, 성공한 경우에만 사용자 작업을 실행하는 선거 계약입니다.
 */
interface SuspendLeaderElector: LeaderElectionState {

    /**
     * `runIfLeader`는 leadership을 획득한 경우에만 action을 실행하고, 획득하지 못하면 null을 반환합니다.
     *
     * @param lockName 획득할 lock의 이름
     * @param action lock 획득 후 실행할 suspend 작업
     * @return 획득한 경우 작업 결과, contention으로 획득하지 못한 경우 `null`
     */
    suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T?

    /**
     * action에서 발생한 `Exception`은 `ActionFailed`로 반환하고, lock 획득 중 발생한 예외와 coroutine 취소는 전파합니다.
     * @param lockName 획득할 lock의 이름
     * @param action lock 획득 후 실행할 suspend 작업
     * @return 획득 결과, contention skip, action 실패를 구분하는 `LeaderRunResult`
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
     * `runIfLeader`는 leadership을 획득한 경우에만 action을 실행하고, 획득하지 못하면 null을 반환합니다.
     *
     * 기존 구현과의 호환 경로로 `slot.lockName`만 사용하고 `LeaderElectorBridgeLog` 경고를 기록합니다.
     * @param slot lock 이름과 요청 leader ID를 담은 그룹 선거 slot
     * @param action lock 획득 후 실행할 suspend 작업
     * @return 획득한 경우 작업 결과, contention으로 획득하지 못한 경우 `null`
     */
    suspend fun <T> runIfLeader(slot: LeaderSlot, action: suspend () -> T): T? {
        LeaderElectorBridgeLog.global().warnOnBridgeUse(this::class, slot)
        return runIfLeader(slot.lockName, action)
    }

    /**
     * 기존 구현과의 호환 경로로 `slot.lockName`만 사용하고 `LeaderElectorBridgeLog` 경고를 기록합니다.
     * action 실패는 `ActionFailed`로 반환하며, lock 획득 예외와 coroutine 취소는 전파합니다.
     * @param slot lock 이름과 요청 leader ID를 담은 그룹 선거 slot
     * @param action lock 획득 후 실행할 suspend 작업
     * @return 획득 결과, contention skip, action 실패를 구분하는 `LeaderRunResult`
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
