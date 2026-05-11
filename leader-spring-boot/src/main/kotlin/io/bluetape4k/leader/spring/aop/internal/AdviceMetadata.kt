package io.bluetape4k.leader.spring.aop.internal

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory

/**
 * `@LeaderElection` 처리 aspect 가 메서드별로 캐싱하는 resolved metadata.
 *
 * ## 필드 설명
 * - [nameExpression]: `@LeaderElection.name` 원본 (SpEL 또는 literal)
 * - [literalName]: literal pattern 에 해당하면 미리 파싱된 값, SpEL 이면 `null`
 * - [options]: resolved [LeaderElectionOptions]
 * - [factoryBeanName]: sync elector factory bean name (진단 metadata + [LockIdentity] 구성용)
 * - [factory]: sync elector factory 인스턴스
 * - [failureMode]: INHERIT 이 이미 resolve 된 effective failure mode
 * - [leaseTimeWarnThresholdNanos]: leaseTime × 0.8 임계값 (ns)
 * - [branch]: [AdviceBranch] — SYNC / SUSPEND / MONO
 * - [suspendElectorFactory]: SUSPEND / MONO 분기 factory (`null` for SYNC)
 * - [suspendElectorFactoryBeanName]: SUSPEND / MONO 분기 factory bean name
 * - [annotationKind]: [LockIdentity.AnnotationKind.SINGLE] (LeaderElection 전용)
 * - [groupParams]: `null` (group 아님 — LeaderElection 전용 항상 `null`)
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
    val suspendElectorFactory: SuspendLeaderElectorFactory?,
    val suspendElectorFactoryBeanName: String,
    val annotationKind: LockIdentity.AnnotationKind = LockIdentity.AnnotationKind.SINGLE,
    val groupParams: LockIdentity.GroupParams? = null,
) {
    val isSuspend: Boolean get() = branch == AdviceBranch.COROUTINES
    val isMono: Boolean get() = branch == AdviceBranch.REACTIVE

    /**
     * 주어진 [branch] 에 맞는 [LockIdentity] 를 생성합니다.
     *
     * `factoryBeanName` 은 `equals/hashCode` 제외이므로 sync ↔ suspend nested 호출 시에도
     * 동일 lock 으로 인식됩니다 (Step 3-P R3 mitigation).
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
