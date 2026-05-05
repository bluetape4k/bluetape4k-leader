package io.bluetape4k.leader.spring.aop

import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectionException
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import io.bluetape4k.leader.annotation.LeaderGroupElection
import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.metrics.SkipReason
import io.bluetape4k.leader.spring.aop.cache.GroupFactoryCacheKey
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.leader.spring.aop.util.AnnotationLookup
import io.bluetape4k.leader.spring.aop.util.DurationParser
import io.bluetape4k.leader.spring.aop.util.LockNameValidator
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireGe
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.beans.factory.SmartInitializingSingleton
import java.lang.reflect.Method
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.nanoseconds

/**
 * `@LeaderGroupElection` 어노테이션 처리 Aspect — sync `T?` only.
 *
 * [LeaderElectionAspect] 와 동일 패턴 + `maxLeaders` 분기. backend 예외는
 * [LeaderGroupElectionException] 으로 wrapping.
 *
 * Fix-94: [resolveLockName] + [factory.create] 를 try 안으로 이동 —
 * backend I/O 실패가 failureMode 우회하던 버그 수정.
 */
@Aspect
class LeaderGroupElectionAspect(
    private val beanSelector: LeaderBeanSelector,
    private val props: LeaderAopProperties,
    private val spel: SpelExpressionEvaluator,
    private val lockNameValidator: LockNameValidator,
    private val recorders: List<LeaderAopMetricsRecorder>,
) : SmartInitializingSingleton {

    private val metadataCache = ConcurrentHashMap<Method, GroupAdviceMetadata>()
    private val factoryCache = ConcurrentHashMap<GroupFactoryCacheKey, LeaderGroupElector>()
    private val hasRecorders = recorders.isNotEmpty()

    @Around("@annotation(io.bluetape4k.leader.annotation.LeaderGroupElection)")
    fun aroundLeader(pjp: ProceedingJoinPoint): Any? {
        val method = (pjp.signature as MethodSignature).method
        val target = pjp.target
        val args = pjp.args

        val meta = metadataCache.computeIfAbsent(method) { resolveMetadata(it, target) }
        val opts = meta.options
        val coreOpts = opts.toCoreOptions()

        // [Fix-94] resolveLockName + factory.create 를 try 안으로 이동
        var lockName: String? = null
        val start = System.nanoTime()

        return try {
            val resolvedName = resolveLockName(meta, method, args, target)
            lockName = resolvedName

            val cacheKey = GroupFactoryCacheKey(meta.factoryBeanName, opts)
            val election = factoryCache.computeIfAbsent(cacheKey) { meta.factory.create(opts) }

            fanOut { it.onLockAttempt(resolvedName, coreOpts) }

            val result = election.runIfLeader(resolvedName) {
                fanOut {
                    it.onLockAcquired(resolvedName, coreOpts, (System.nanoTime() - start).nanoseconds)
                    it.onTaskStarted(resolvedName)
                }
                executeBody(pjp, resolvedName, start)
            }
            if (result == null) {
                fanOut { it.onLockNotAcquired(resolvedName, coreOpts, SkipReason.CONTENTION) }
                log.debug { "leader.aop.skipped lockName=$resolvedName reason=CONTENTION" }
            } else {
                val elapsed = System.nanoTime() - start
                fanOut { it.onTaskFinished(resolvedName, elapsed.nanoseconds) }
                log.debug { "leader.aop.elected lockName=$resolvedName elapsedNs=$elapsed" }
                if (elapsed > meta.leaseTimeWarnThresholdNanos) {
                    log.warn {
                        "leader.aop.lease-warn lockName=$resolvedName elapsedNs=$elapsed leaseTimeNs=${opts.leaseTime.toNanos()}"
                    }
                }
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (bodyMarker: BodyThrownMarker) {
            throw bodyMarker.cause
        } catch (backendEx: Throwable) {
            val effectiveName = lockName ?: "<unresolved:${meta.nameExpression}>"
            val wrapped = LeaderGroupElectionException("leader group backend error for lock '$effectiveName'", backendEx)
            fanOut {
                it.onLockNotAcquired(effectiveName, coreOpts, SkipReason.BACKEND_ERROR)
                it.onTaskFailed(effectiveName, (System.nanoTime() - start).nanoseconds, backendEx)
            }
            when (meta.failureMode) {
                LeaderAspectFailureMode.INHERIT -> error("INHERIT must be resolved in resolveMetadata")
                LeaderAspectFailureMode.RETHROW -> throw wrapped
                LeaderAspectFailureMode.SKIP -> {
                    log.warn(backendEx) { "leader.aop.skipped lockName=$effectiveName reason=BACKEND_ERROR" }
                    null
                }
            }
        }
    }

    private fun executeBody(pjp: ProceedingJoinPoint, lockName: String, start: Long): Any? {
        return try {
            pjp.proceed()
        } catch (e: CancellationException) {
            fanOut { it.onTaskFailed(lockName, (System.nanoTime() - start).nanoseconds, e) }
            throw e
        } catch (bodyEx: Throwable) {
            fanOut { it.onTaskFailed(lockName, (System.nanoTime() - start).nanoseconds, bodyEx) }
            throw BodyThrownMarker(bodyEx)
        }
    }

    /** Body throw vs backend throw 구분용 internal marker. */
    private class BodyThrownMarker(override val cause: Throwable) : RuntimeException(cause)

    override fun afterSingletonsInstantiated() {
        // BPP/Validator 가 별도 검증 — Aspect 는 캐시 워밍 미수행
    }

    private fun resolveLockName(
        meta: GroupAdviceMetadata,
        method: Method,
        args: Array<Any?>,
        target: Any,
    ): String {
        val rawName = if (meta.literalName != null) {
            meta.literalName
        } else {
            spel.evaluate(meta.nameExpression, method, args, target)
        }
        val prefixed = lockNameValidator.applyPrefix(rawName)
        lockNameValidator.validate(prefixed)
        return prefixed
    }

    private fun resolveMetadata(method: Method, target: Any): GroupAdviceMetadata {
        val ann = AnnotationLookup.findAnnotationWithTargetFallback(method, target, LeaderGroupElection::class.java)
            ?: error("@LeaderGroupElection not found on ${method.declaringClass.name}#${method.name}")

        ann.maxLeaders.requireGe(2, "ann.maxLeaders")

        val waitTime = DurationParser.parseOrDefault(ann.waitTime, props.defaultWaitTime)
        val leaseTime = DurationParser.parseOrDefault(ann.leaseTime, props.defaultLeaseTime)

        val opts = LeaderGroupElectionOptions(
            maxLeaders = ann.maxLeaders,
            waitTime = waitTime,
            leaseTime = leaseTime,
        )
        val selected = beanSelector.selectGroupElectionFactory(ann.bean)
        val literal = if (LITERAL_PATTERN.matches(ann.name)) ann.name else null

        val effectiveFailureMode = if (ann.failureMode == LeaderAspectFailureMode.INHERIT) {
            props.failureMode
        } else {
            ann.failureMode
        }

        return GroupAdviceMetadata(
            nameExpression = ann.name,
            literalName = literal,
            options = opts,
            factoryBeanName = selected.beanName,
            factory = selected.bean,
            failureMode = effectiveFailureMode,
            leaseTimeWarnThresholdNanos = (leaseTime.toNanos() * LEASE_WARN_RATIO).toLong(),
        )
    }

    private inline fun fanOut(crossinline action: (LeaderAopMetricsRecorder) -> Unit) {
        if (!hasRecorders) return
        for (recorder in recorders) {
            runCatching { action(recorder) }
                .onFailure { log.warn(it) { "metrics recorder threw" } }
        }
    }

    private fun LeaderGroupElectionOptions.toCoreOptions(): io.bluetape4k.leader.LeaderElectionOptions =
        io.bluetape4k.leader.LeaderElectionOptions(waitTime = waitTime, leaseTime = leaseTime)

    private data class GroupAdviceMetadata(
        val nameExpression: String,
        val literalName: String?,
        val options: LeaderGroupElectionOptions,
        val factoryBeanName: String,
        val factory: LeaderGroupElectorFactory,
        val failureMode: LeaderAspectFailureMode,
        val leaseTimeWarnThresholdNanos: Long,
    )

    companion object: KLogging() {
        private val LITERAL_PATTERN = Regex("^[A-Za-z0-9_:.\\-]+$")
        private const val LEASE_WARN_RATIO = 0.8
    }
}
