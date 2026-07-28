package io.bluetape4k.leader

import io.bluetape4k.leader.coroutines.LockHandleElement
import io.bluetape4k.leader.internal.LockStateHolder
import io.bluetape4k.logging.KLogging
import kotlin.coroutines.coroutineContext

/**
 * `LockAssert` 선언은 leader election 계약에서 사용되는 object입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
object LockAssert : KLogging() {

    /**
     * `assertLocked` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    @JvmStatic
    fun assertLocked() {
        val handle = LockStateHolder.peekSync()
            ?: error("LockAssert.assertLocked() called outside an active @LeaderElection / @LeaderGroupElection scope")
        check(handle !is LeaderLockHandle.FailOpen) {
            "LockAssert.assertLocked() — current scope is fail-open (no real lock held). lockName=${handle.lockName}"
        }
    }

    /**
     * `assertLocked` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    @JvmStatic
    fun assertLocked(lockName: String) {
        val handle = LockStateHolder.peekSyncMatching(lockName)
            ?: error("LockAssert.assertLocked('$lockName') — no active scope with this lock")
        check(handle !is LeaderLockHandle.FailOpen) {
            "LockAssert.assertLocked('$lockName') — current scope is fail-open"
        }
    }

    /**
     * `isLocked` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    @JvmStatic
    fun isLocked(): Boolean {
        val handle = LockStateHolder.peekSync() ?: return false
        return handle is LeaderLockHandle.Real
    }

    /**
     * `isLocked` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    @JvmStatic
    fun isLocked(lockName: String): Boolean {
        val handle = LockStateHolder.peekSyncMatching(lockName) ?: return false
        return handle is LeaderLockHandle.Real
    }

    /**
     * `assertLockedSuspend` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    suspend fun assertLockedSuspend() {
        val handle = coroutineContext[LockHandleElement]?.handle
            ?: error("LockAssert.assertLockedSuspend() called outside an active @LeaderElection scope")
        check(handle !is LeaderLockHandle.FailOpen) {
            "LockAssert.assertLockedSuspend() — current scope is fail-open. lockName=${handle.lockName}"
        }
    }

    /**
     * `assertLockedSuspend` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    suspend fun assertLockedSuspend(lockName: String) {
        val handle = coroutineContext[LockHandleElement]?.handle
            ?: error("LockAssert.assertLockedSuspend('$lockName') — no active scope")
        check(handle.lockName == lockName) {
            "LockAssert.assertLockedSuspend('$lockName') — active lock is '${handle.lockName}'"
        }
        check(handle !is LeaderLockHandle.FailOpen) {
            "LockAssert.assertLockedSuspend('$lockName') — current scope is fail-open"
        }
    }

    /**
     * `isLockedSuspend` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    suspend fun isLockedSuspend(): Boolean {
        val handle = coroutineContext[LockHandleElement]?.handle ?: return false
        return handle is LeaderLockHandle.Real
    }

    /**
     * `isLockedSuspend` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    suspend fun isLockedSuspend(lockName: String): Boolean {
        val handle = coroutineContext[LockHandleElement]?.handle ?: return false
        return handle is LeaderLockHandle.Real && handle.lockName == lockName
    }
}
