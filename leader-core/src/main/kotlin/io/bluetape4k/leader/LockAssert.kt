package io.bluetape4k.leader

import io.bluetape4k.leader.coroutines.LockHandleElement
import io.bluetape4k.leader.internal.LockStateHolder
import io.bluetape4k.logging.KLogging
import kotlinx.coroutines.currentCoroutineContext

/**
 * 현재 thread 또는 coroutine의 leader lock 실행 scope를 검사하고 조회합니다.
 *
 * `assertLocked` 계열은 활성 scope가 실제 lock을 보유하는지 확인하며,
 * `isLocked` 계열은 현재 scope의 lock 보유 여부를 반환합니다.
 */
object LockAssert: KLogging() {

    /**
     * 현재 동기 실행 scope가 실제 lock을 보유하는지 확인합니다.
     *
     * `@LeaderElection` 또는 `@LeaderGroupElection` scope가 없거나 fail-open scope이면 `IllegalStateException`을 던집니다.
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
     * 지정한 lock의 동기 실행 scope가 실제 lock을 보유하는지 확인합니다.
     *
     * 활성 scope가 없거나 `lockName`이 현재 scope의 lock 이름과 다르거나 fail-open scope이면 `IllegalStateException`을 던집니다.
     * @param lockName 현재 동기 실행 scope와 일치해야 하는 lock 이름
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
     * 현재 동기 실행 scope가 실제 lock을 보유하는지 조회합니다.
     *
     * 현재 thread에 활성 scope가 없거나 실제 lock을 보유하지 않은 scope이면 `false`를 반환합니다.
     * @return 현재 동기 실행 scope가 실제 lock을 보유하면 `true`
     */
    @JvmStatic
    fun isLocked(): Boolean {
        val handle = LockStateHolder.peekSync() ?: return false
        return handle is LeaderLockHandle.Real
    }

    /**
     * 지정한 lock의 동기 실행 scope가 실제 lock을 보유하는지 조회합니다.
     *
     * 일치하는 활성 scope가 없거나 실제 lock을 보유하지 않은 scope이면 `false`를 반환합니다.
     * @param lockName 현재 동기 실행 scope와 비교할 lock 이름
     * @return 지정한 lock의 현재 동기 실행 scope가 실제 lock을 보유하면 `true`
     */
    @JvmStatic
    fun isLocked(lockName: String): Boolean {
        val handle = LockStateHolder.peekSyncMatching(lockName) ?: return false
        return handle is LeaderLockHandle.Real
    }

    /**
     * 현재 coroutine 실행 scope가 실제 lock을 보유하는지 확인합니다.
     *
     * 활성 `@LeaderElection` scope가 없거나 fail-open scope이면 `IllegalStateException`을 던집니다.
     */
    suspend fun assertLockedSuspend() {
        val handle = currentCoroutineContext()[LockHandleElement]?.handle
            ?: error("LockAssert.assertLockedSuspend() called outside an active @LeaderElection scope")

        check(handle !is LeaderLockHandle.FailOpen) {
            "LockAssert.assertLockedSuspend() — current scope is fail-open. lockName=${handle.lockName}"
        }
    }

    /**
     * 지정한 lock의 coroutine 실행 scope가 실제 lock을 보유하는지 확인합니다.
     *
     * 활성 scope가 없거나 `lockName`이 현재 scope의 lock 이름과 다르거나 fail-open scope이면 `IllegalStateException`을 던집니다.
     * @param lockName 현재 coroutine 실행 scope와 일치해야 하는 lock 이름
     */
    suspend fun assertLockedSuspend(lockName: String) {
        val handle = currentCoroutineContext()[LockHandleElement]?.handle
            ?: error("LockAssert.assertLockedSuspend('$lockName') — no active scope")

        check(handle.lockName == lockName) {
            "LockAssert.assertLockedSuspend('$lockName') — active lock is '${handle.lockName}'"
        }
        check(handle !is LeaderLockHandle.FailOpen) {
            "LockAssert.assertLockedSuspend('$lockName') — current scope is fail-open"
        }
    }

    /**
     * 현재 coroutine 실행 scope가 실제 lock을 보유하는지 조회합니다.
     *
     * 현재 coroutine context에 활성 scope가 없거나 실제 lock을 보유하지 않은 scope이면 `false`를 반환합니다.
     * @return 현재 coroutine 실행 scope가 실제 lock을 보유하면 `true`
     */
    suspend fun isLockedSuspend(): Boolean {
        val handle = currentCoroutineContext()[LockHandleElement]?.handle ?: return false
        return handle is LeaderLockHandle.Real
    }

    /**
     * 지정한 lock의 coroutine 실행 scope가 실제 lock을 보유하는지 조회합니다.
     *
     * 현재 coroutine context에 일치하는 활성 scope가 없거나 실제 lock을 보유하지 않은 scope이면 `false`를 반환합니다.
     * @param lockName 현재 coroutine 실행 scope와 비교할 lock 이름
     * @return 지정한 lock의 현재 coroutine 실행 scope가 실제 lock을 보유하면 `true`
     */
    suspend fun isLockedSuspend(lockName: String): Boolean {
        val handle = currentCoroutineContext()[LockHandleElement]?.handle ?: return false
        return handle is LeaderLockHandle.Real && handle.lockName == lockName
    }
}
