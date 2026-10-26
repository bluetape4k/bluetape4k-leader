package io.bluetape4k.leader

import io.bluetape4k.leader.identity.LeaderElectorBridgeLog
import java.util.concurrent.CancellationException

/**
 * `LeaderGroupElector`는 여러 slot을 허용하는 blocking group leader election 실행자입니다.
 *
 * `runIfLeader`는 lock 획득에 성공한 slot에서만 작업을 실행하며, 결과 API는 획득·skip·작업 실패를 구분합니다.
 */
interface LeaderGroupElector: AsyncLeaderGroupElector {

    /**
     * `runIfLeader`는 leadership을 획득한 경우에만 action을 실행하고, 획득하지 못하면 null을 반환합니다.
     *
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @param action leadership을 획득한 경우에만 실행되는 사용자 작업입니다.
     * @return lock slot을 획득해 실행한 `action`의 결과이며, 획득에 실패하면 `null`입니다.
     */
    fun <T> runIfLeader(lockName: String, action: () -> T): T?

    /**
     * `runIfLeaderResult`는 leadership 획득, skip, action 실패를 명시적인 LeaderRunResult로 반환합니다.
     *
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @param action leadership을 획득한 경우에만 실행되는 사용자 작업입니다.
     * @return 획득 성공은 `LeaderRunResult.Elected`, slot contention은 `LeaderRunResult.Skipped`, 실행 중 예외는
     * `LeaderRunResult.ActionFailed`로 반환합니다.
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
     * @param slot group election slot과 audit leader id를 함께 전달하는 값입니다.
     * @param action leadership을 획득한 경우에만 실행되는 사용자 작업입니다.
     * @return lock slot을 획득해 실행한 `action`의 결과이며, 획득에 실패하면 `null`입니다.
     */
    fun <T> runIfLeader(slot: LeaderSlot, action: () -> T): T? {
        LeaderElectorBridgeLog.global().warnOnBridgeUse(this::class, slot)
        return runIfLeader(slot.lockName, action)
    }

    /**
     * `runIfLeaderResult`는 leadership 획득, skip, action 실패를 명시적인 LeaderRunResult로 반환합니다.
     *
     * @param slot group election slot과 audit leader id를 함께 전달하는 값입니다.
     * @param action leadership을 획득한 경우에만 실행되는 사용자 작업입니다.
     * @return 획득 성공은 `LeaderRunResult.Elected`, slot contention은 `LeaderRunResult.Skipped`, 실행 중 예외는
     * `LeaderRunResult.ActionFailed`로 반환합니다.
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
