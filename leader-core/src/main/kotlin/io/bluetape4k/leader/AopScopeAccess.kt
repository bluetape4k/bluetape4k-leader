package io.bluetape4k.leader

import io.bluetape4k.leader.coroutines.LockHandleElement
import io.bluetape4k.leader.internal.LeaderLockHandleCapture
import io.bluetape4k.leader.internal.LockStateHolder

/**
 * AOP aspect (`leader-spring-boot`) 이 `leader-core` 의 `internal` scope 관리 심볼에 접근하기 위한 공개 브리지.
 *
 * ## 용도 제한
 * - **AOP aspect 전용 API** — 일반 애플리케이션 코드에서 직접 사용 금지.
 * - 일반 코드에서는 [LockAssert] / [LockExtender] 만 사용.
 *
 * ## 노출 범위
 * | 심볼 | 용도 |
 * |------|------|
 * | [peekSyncMatching] | sync 분기 reentrant peek (동일 lockName 보유 여부 확인) |
 * | [withPushedSync] | sync 분기 handle push/pop — body 실행 전후 LockStateHolder 관리 |
 * | [pollCapture] | elector → aspect handle 전달 수신 (CaptureScope.runWithCapture 후) |
 * | [createFailOpen] | fail-open sentinel handle 생성 |
 * | [createLockHandleElement] | CoroutineContext 에 주입할 LockHandleElement 생성 |
 */
object AopScopeAccess {

    /**
     * 현재 스레드의 lock stack 에서 [lockName] 과 일치하는 handle 을 peek 합니다.
     *
     * aspect 가 sync 분기 진입 전 reentrant 여부를 판정할 때 사용합니다.
     */
    fun peekSyncMatching(lockName: String): LeaderLockHandle? =
        LockStateHolder.peekSyncMatching(lockName)

    /**
     * handle 을 sync lock stack 에 push 하고 [block] 실행 후 pop 합니다.
     *
     * try/finally 로 누수 차단. fail-open sentinel 또는 reentrant passthrough handle 을 주입할 때 사용합니다.
     *
     * Note: public 브리지이므로 `inline` 불가 — internal `LockStateHolder.withPushed` inline 직접 위임 대신
     * push/pop 을 명시적으로 구현.
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
     * [LeaderLockHandleCapture] ThreadLocal 에서 handle 을 꺼내고 즉시 clear 합니다.
     *
     * group elector 의 `CaptureScope.runWithCapture` 가 set 한 값을 aspect 가 수신합니다.
     * single elector 는 capture 하지 않으므로 `null` 이 정상 — CaptureInvariantException 사용 금지.
     */
    fun pollCapture(): LeaderLockHandle.Real? = LeaderLockHandleCapture.poll()

    /**
     * Fail-open sentinel [LeaderLockHandle.FailOpen] 을 생성합니다.
     *
     * `failureMode = FAIL_OPEN_RUN` 분기에서 body 실행 시 [LockAssert] / [LockExtender] 가
     * fail-open scope 를 인식할 수 있도록 stack 에 push 합니다.
     */
    fun createFailOpen(identity: LockIdentity): LeaderLockHandle.FailOpen =
        LeaderLockHandle.failOpen(identity)

    /**
     * [LeaderLockHandle.Real] 의 reentry depth 를 증가시킨 passthrough copy 를 생성합니다.
     *
     * 동일 lock 의 reentrant 진입 시 aspect 가 새 depth 를 가진 handle 을 push 합니다.
     */
    fun incrementReentryDepth(handle: LeaderLockHandle.Real): LeaderLockHandle.Real =
        handle.withReentryDepth(handle.reentryDepth + 1)

    /**
     * aspect 가 coroutine context 에 주입할 [LockHandleElement] 를 생성합니다.
     *
     * suspend / Mono 분기에서 `withContext(LeaderElectionInfo(...) + createLockHandleElement(handle))` 패턴으로 사용합니다.
     */
    fun createLockHandleElement(handle: LeaderLockHandle): LockHandleElement =
        LockHandleElement(handle)

    /**
     * 테스트 또는 aspect 가 synthetic `LeaderLockHandle.Real` 을 생성할 때 사용합니다.
     *
     * 실제 backend 없이 reentrant 단위 테스트를 작성할 때 `LockStateHolder` 에 push 할 handle 을 만드는 용도입니다.
     * Production aspect 코드에서는 elector 가 생성한 handle 만 사용합니다.
     */
    fun createSyntheticReal(
        lockName: String,
        factoryBeanName: String,
        token: String = "test-token",
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
}
