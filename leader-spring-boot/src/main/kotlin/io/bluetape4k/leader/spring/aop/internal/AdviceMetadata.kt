package io.bluetape4k.leader.spring.aop.internal

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory

/**
 * Resolved metadata cached per-method by the `@LeaderElection` processing aspect.
 *
 * ## Field Description
 * - [nameExpression]: Raw `@LeaderElection.name` value (SpEL expression or literal)
 * - [literalName]: Pre-parsed value when the name matches the literal pattern; `null` for SpEL
 * - [options]: Resolved [LeaderElectionOptions]
 * - [factoryBeanName]: Sync elector factory bean name (used for diagnostic metadata and [LockIdentity] construction)
 * - [factory]: Sync elector factory instance
 * - [failureMode]: Effective failure mode with INHERIT already resolved
 * - [leaseTimeWarnThresholdNanos]: leaseTime × 0.8 threshold in nanoseconds
 * - [branch]: [AdviceBranch] — SYNC / COROUTINES / REACTIVE
 * - [isSuspend], [isMono], [isFlux], [isFlow]: Exact method return-shape markers
 * - [streamBounded]: Caller opt-in for finite `Flux` / `Flow` streams without auto-extension
 * - [suspendElectorFactory]: Factory for the SUSPEND / MONO branch (`null` for SYNC)
 * - [suspendElectorFactoryBeanName]: Factory bean name for the SUSPEND / MONO branch
 * - [annotationKind]: [LockIdentity.AnnotationKind.SINGLE] (exclusive to LeaderElection)
 * - [groupParams]: `null` (not a group — always `null` for LeaderElection)
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
     * Creates a [LockIdentity] for the given [branch].
     *
     * `factoryBeanName` is excluded from `equals/hashCode`, so nested sync ↔ suspend calls
     * are recognised as the same lock (Step 3-P R3 mitigation).
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
