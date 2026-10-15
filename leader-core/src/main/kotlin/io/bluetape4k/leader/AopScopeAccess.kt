package io.bluetape4k.leader

import io.bluetape4k.leader.coroutines.LockHandleElement
import io.bluetape4k.leader.internal.LeaderLockHandleCapture
import io.bluetape4k.leader.internal.LockStateHolder
import io.bluetape4k.leader.internal.NoopExtendDelegate

/**
 * AOP 경계에서 현재 lock handle을 읽고 실행 문맥에 임시로 연결하는 내부 access point입니다.
 *
 */
object AopScopeAccess {

    /**
     * 현재 thread-local 문맥에서 가장 최근에 push된, 이름이 일치하는 handle을 찾습니다.
     * @param lockName 조회할 handle의 lock 이름입니다.
     * @return 일치하는 handle이 있으면 반환하고, 없으면 null입니다.
     */
    fun peekSyncMatching(lockName: String): LeaderLockHandle? =
        LockStateHolder.peekSyncMatching(lockName)

    /**
     * block 실행 동안 handle을 thread-local lock 문맥에 push하고, 성공·예외 여부와 관계없이 제거합니다.
     * @param handle block 안에서 현재 lock으로 사용할 handle입니다.
     * @param block handle이 문맥에 등록된 상태에서 실행할 작업입니다.
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
     * 현재 thread의 instrumentation capture를 반환하고 저장된 값을 제거합니다.
     * @return capture된 실제 handle이 있으면 반환하고, 없으면 null입니다.
     */
    fun pollCapture(): LeaderLockHandle.Real? = LeaderLockHandleCapture.poll()

    /**
     * instrumentation 경계에서 관측할 실제 lock 획득 정보를 저장합니다.
     * @param handle 이후 관측 지점에서 읽을 `Real` lock handle입니다.
     */
    fun setCapture(handle: LeaderLockHandle.Real) {
        LeaderLockHandleCapture.set(handle)
    }

    /**
     * instrumentation 경계에 저장된 lock 획득 정보를 제거합니다.
     */
    fun clearCapture() {
        LeaderLockHandleCapture.clear()
    }

    /**
     * 실제 backend lock이 없는 fail-open 경로를 표시하는 sentinel handle을 생성합니다.
     * @param identity fail-open 처리 대상 lock의 이름·kind·slot 식별자입니다.
     * @return `FailOpen` sentinel입니다. backend에 보유한 lock을 나타내지 않습니다.
     */
    fun createFailOpen(identity: LockIdentity): LeaderLockHandle.FailOpen =
        LeaderLockHandle.failOpen(identity)

    /**
     * 현재 handle의 재진입 깊이를 1 증가시킨 복사본을 만듭니다.
     * @param handle 깊이를 증가시킬 실제 lock handle입니다.
     * @return 증가된 깊이를 가진 새 `Real` handle입니다.
     */
    fun incrementReentryDepth(handle: LeaderLockHandle.Real): LeaderLockHandle.Real =
        handle.withReentryDepth(handle.reentryDepth + 1)

    /**
     * handle을 coroutine context element로 감싸 suspend 호출에서 소유권을 전달합니다.
     * @param handle coroutine context에 연결할 lock handle입니다.
     * @return 지정한 handle을 담은 `LockHandleElement`입니다.
     */
    fun createLockHandleElement(handle: LeaderLockHandle): LockHandleElement =
        LockHandleElement(handle)

    /**
     * 실제 backend lease를 획득하지 않고 AOP instrumentation용 단일 lock handle을 만듭니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @param factoryBeanName handle을 만든 factory bean의 Spring 이름입니다.
     * @param token instrumentation identity에 쓸 synthetic token입니다. 기본값은 `test-token`입니다.
     * @return backend lease 연장을 수행하지 않는 synthetic `Real` handle입니다.
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
            extendDelegate = NoopExtendDelegate,
        )
    }

    /**
     * 실제 backend lease를 획득하지 않고 AOP instrumentation용 group lock handle을 만듭니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @param factoryBeanName handle을 만든 factory bean의 Spring 이름입니다.
     * @param maxLeaders 동시에 leadership을 획득할 수 있는 최대 슬롯 수입니다.
     * @param slotId synthetic group slot ID입니다. 기본값은 `0`입니다.
     * @param token instrumentation identity에 쓸 synthetic token입니다. 기본값은 `test-group-token`입니다.
     * @return backend lease 연장을 수행하지 않는 synthetic `Real` handle입니다.
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
            extendDelegate = NoopExtendDelegate,
        )
    }

    /**
     * AOP single-lock synthetic handle에 사용하는 표시용 token입니다. backend credential이 아닙니다.
     */
    private const val SYNTHETIC_SINGLE_TOKEN = "test-token"

    /**
     * AOP group-lock synthetic handle에 사용하는 표시용 token입니다. backend credential이 아닙니다.
     */
    private const val SYNTHETIC_GROUP_TOKEN = "test-group-token"

    /**
     * AOP synthetic group handle의 기본 slot ID입니다.
     */
    private const val SYNTHETIC_DEFAULT_SLOT = "0"
}
