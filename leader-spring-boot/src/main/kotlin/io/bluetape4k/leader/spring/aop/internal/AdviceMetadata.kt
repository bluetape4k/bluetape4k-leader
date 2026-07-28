package io.bluetape4k.leader.spring.aop.internal

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory

/**
 * `AdviceMetadata`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property nameExpression Spring Boot integration 계약에서 `nameExpression` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property literalName Spring Boot integration 계약에서 `literalName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property options Spring Boot integration 계약에서 `options` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property factoryBeanName Spring Boot integration 계약에서 `factoryBeanName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property factory Spring Boot integration 계약에서 `factory` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property failureMode Spring Boot integration 계약에서 `failureMode` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property leaseTimeWarnThresholdNanos Spring Boot integration 계약에서 `leaseTimeWarnThresholdNanos` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property branch Spring Boot integration 계약에서 `branch` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property isSuspend Spring Boot integration 계약에서 `isSuspend` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property isMono Spring Boot integration 계약에서 `isMono` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property isFlux Spring Boot integration 계약에서 `isFlux` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property isFlow Spring Boot integration 계약에서 `isFlow` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property streamBounded Spring Boot integration 계약에서 `streamBounded` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property suspendElectorFactory Spring Boot integration 계약에서 `suspendElectorFactory` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property suspendElectorFactoryBeanName Spring Boot integration 계약에서 `suspendElectorFactoryBeanName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property annotationKind Spring Boot integration 계약에서 `annotationKind` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property groupParams Spring Boot integration 계약에서 `groupParams` 값을 계산하거나 전달할 때 사용하는 속성입니다.
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
) {
    /**
     * `resolveLockIdentity` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
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
}
