package io.bluetape4k.leader.spring.aop.internal

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import java.io.Serializable

/**
 * 하나의 advice 대상 메서드에 대해 해석한 lock, factory, 반환 모델 metadata입니다.
 *
 * @property nameExpression annotation에 선언된 lock 이름 SpEL 또는 literal 표현식입니다.
 * @property literalName 표현식이 literal일 때의 미리 계산한 lock 이름이며 동적 표현식이면 `null`입니다.
 * @property options blocking elector factory에 전달할 단일 leader 옵션입니다.
 * @property factoryBeanName blocking 실행에 사용할 factory bean 이름입니다.
 * @property factory blocking 실행에 사용할 elector factory입니다.
 * @property failureMode backend 오류 또는 lock 미획득 시 적용할 advice 실패 정책입니다.
 * @property leaseTimeWarnThresholdNanos 실행 시간이 이 나노초 임계값을 넘을 때 경고합니다.
 * @property branch metadata가 기본으로 해석한 실행 branch입니다.
 * @property isSuspend 대상 메서드가 Kotlin `suspend` 함수인지 여부입니다.
 * @property isMono 반환 타입이 Reactor `Mono`인지 여부입니다.
 * @property isFlux 반환 타입이 Reactor `Flux`인지 여부입니다.
 * @property isFlow 반환 타입이 Kotlin `Flow`인지 여부입니다.
 * @property streamBounded 스트림이 lease 내에 종료된다는 annotation 설정인지 여부입니다.
 * @property suspendElectorFactory coroutine/reactive branch에서 사용할 suspend factory이며 blocking branch에서는 `null`입니다.
 * @property suspendElectorFactoryBeanName coroutine/reactive 실행에 사용할 factory bean 이름입니다.
 * @property annotationKind lock identity에 기록할 single/group annotation 종류입니다.
 * @property groupParams group annotation이 제공한 semaphore 파라미터이며 single annotation에서는 `null`입니다.
 */
internal data class AdviceMetadata(
    val nameExpression: String,
    val literalName: String?,
    val options: LeaderElectionOptions,
    val factoryBeanName: String,
    val factory: LeaderElectorFactory,
    val failureMode: LeaderAspectFailureMode,
    val leaseTimeWarnThresholdNanos: Long,
    val branch: AdviceBranch,
    val isSuspend: Boolean,
    val isMono: Boolean,
    val isFlux: Boolean,
    val isFlow: Boolean,
    val streamBounded: Boolean,
    val suspendElectorFactory: SuspendLeaderElectorFactory?,
    val suspendElectorFactoryBeanName: String,
    val annotationKind: LockIdentity.AnnotationKind = LockIdentity.AnnotationKind.SINGLE,
    val groupParams: LockIdentity.GroupParams? = null,
): Serializable {
    /** 실행 branch에 맞는 factory bean을 포함한 `LockIdentity`를 생성합니다. */
    fun resolveLockIdentity(lockName: String, branch: AdviceBranch): LockIdentity {
        val beanName = when (branch) {
            AdviceBranch.SYNC -> factoryBeanName
            AdviceBranch.COROUTINES, AdviceBranch.REACTIVE -> suspendElectorFactoryBeanName
        }
        return LockIdentity(
            lockName = lockName,
            kind = annotationKind,
            factoryBeanName = beanName,
            groupParams = groupParams,
        )
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}
