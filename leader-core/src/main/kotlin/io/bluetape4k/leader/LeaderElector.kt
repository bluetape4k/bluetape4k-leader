package io.bluetape4k.leader

import io.bluetape4k.leader.identity.LeaderElectorBridgeLog
import java.util.concurrent.CancellationException

/**
 * `LeaderElector`는 blocking leader election 실행자입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
interface LeaderElector: AsyncLeaderElector {

    /**
     * `runIfLeader`는 leadership을 획득한 경우에만 action을 실행하고, 획득하지 못하면 null을 반환합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @param action leadership을 획득한 경우에만 실행되는 사용자 작업입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun <T> runIfLeader(lockName: String, action: () -> T): T?

    /**
     * `runIfLeaderResult`는 leadership 획득, skip, action 실패를 명시적인 LeaderRunResult로 반환합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @param action leadership을 획득한 경우에만 실행되는 사용자 작업입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun <T> runIfLeaderResult(lockName: String, action: () -> T): LeaderRunResult<T> {
        var elected = false
        val value = try {
            runIfLeader(lockName) {
                elected = true
                action()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
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
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param slot group election slot과 audit leader id를 함께 전달하는 값입니다.
     * @param action leadership을 획득한 경우에만 실행되는 사용자 작업입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun <T> runIfLeader(slot: LeaderSlot, action: () -> T): T? {
        LeaderElectorBridgeLog.global().warnOnBridgeUse(this::class, slot)
        return runIfLeader(slot.lockName, action)
    }

    /**
     * `runIfLeaderResult`는 leadership 획득, skip, action 실패를 명시적인 LeaderRunResult로 반환합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param slot group election slot과 audit leader id를 함께 전달하는 값입니다.
     * @param action leadership을 획득한 경우에만 실행되는 사용자 작업입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun <T> runIfLeaderResult(slot: LeaderSlot, action: () -> T): LeaderRunResult<T> {
        LeaderElectorBridgeLog.global().warnOnResultBridgeUse(this::class, slot)
        var elected = false
        val value = try {
            runIfLeader(slot.lockName) {
                elected = true
                action()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
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
