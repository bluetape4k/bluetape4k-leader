package io.bluetape4k.leader

import io.bluetape4k.leader.coroutines.LockHandleElement
import io.bluetape4k.leader.internal.LeaderLockHandleCapture
import io.bluetape4k.leader.internal.LockStateHolder

/**
 * `AopScopeAccess` 선언은 leader election 계약에서 사용되는 object입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
object AopScopeAccess {

    /**
     * `peekSyncMatching` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun peekSyncMatching(lockName: String): LeaderLockHandle? =
        LockStateHolder.peekSyncMatching(lockName)

    /**
     * `withPushedSync` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param handle `handle` 호출 또는 상태 계산에 필요한 값입니다.
     * @param block `block` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun <R> withPushedSync(handle: LeaderLockHandle, block: () -> R): R {
        LockStateHolder.push(handle)
        try {
            return block()
        } finally {
            LockStateHolder.pop()
            LockStateHolder.cleanup()
        }
    }

    /**
     * `pollCapture` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun pollCapture(): LeaderLockHandle.Real? = LeaderLockHandleCapture.poll()

    /**
     * `setCapture` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param handle `handle` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun setCapture(handle: LeaderLockHandle.Real) {
        LeaderLockHandleCapture.set(handle)
    }

    /**
     * `clearCapture` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun clearCapture() {
        LeaderLockHandleCapture.clear()
    }

    /**
     * `createFailOpen` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param identity lock 이름, token, kind, slot 정보를 묶은 소유권 식별자입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun createFailOpen(identity: LockIdentity): LeaderLockHandle.FailOpen =
        LeaderLockHandle.failOpen(identity)

    /**
     * `incrementReentryDepth` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param handle `handle` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun incrementReentryDepth(handle: LeaderLockHandle.Real): LeaderLockHandle.Real =
        handle.withReentryDepth(handle.reentryDepth + 1)

    /**
     * `createLockHandleElement` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param handle `handle` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun createLockHandleElement(handle: LeaderLockHandle): LockHandleElement =
        LockHandleElement(handle)

    /**
     * `createSyntheticReal` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @param factoryBeanName `factoryBeanName` 호출 또는 상태 계산에 필요한 값입니다.
     * @param token backend lock을 해제하거나 검증할 때 사용하는 소유권 token입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun createSyntheticReal(
        lockName: String,
        factoryBeanName: String,
        token: String = SYNTHETIC_SINGLE_TOKEN,
    ): LeaderLockHandle.Real {
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.SINGLE,
            factoryBeanName = factoryBeanName,
        )
        return LeaderLockHandle.real(
            identity = identity,
            token = token,
            acquiredAtNanos = System.nanoTime(),
            extendDelegate = io.bluetape4k.leader.internal.NoopExtendDelegate,
        )
    }

    /**
     * `createSyntheticGroupReal` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @param factoryBeanName `factoryBeanName` 호출 또는 상태 계산에 필요한 값입니다.
     * @param maxLeaders 동시에 leadership을 획득할 수 있는 최대 슬롯 수입니다.
     * @param slotId group election backend가 slot을 식별할 때 쓰는 값입니다.
     * @param token backend lock을 해제하거나 검증할 때 사용하는 소유권 token입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun createSyntheticGroupReal(
        lockName: String,
        factoryBeanName: String,
        maxLeaders: Int,
        slotId: String = SYNTHETIC_DEFAULT_SLOT,
        token: String = SYNTHETIC_GROUP_TOKEN,
    ): LeaderLockHandle.Real {
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.GROUP,
            factoryBeanName = factoryBeanName,
            groupParams = LockIdentity.GroupParams(maxLeaders = maxLeaders),
        )
        return LeaderLockHandle.real(
            identity = identity,
            token = token,
            acquiredAtNanos = System.nanoTime(),
            slotId = slotId,
            extendDelegate = io.bluetape4k.leader.internal.NoopExtendDelegate,
        )
    }

    /**
     * `SYNTHETIC_SINGLE_TOKEN` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    private const val SYNTHETIC_SINGLE_TOKEN = "test-token"

    /**
     * `SYNTHETIC_GROUP_TOKEN` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    private const val SYNTHETIC_GROUP_TOKEN = "test-group-token"

    /**
     * `SYNTHETIC_DEFAULT_SLOT` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    private const val SYNTHETIC_DEFAULT_SLOT = "0"
}
