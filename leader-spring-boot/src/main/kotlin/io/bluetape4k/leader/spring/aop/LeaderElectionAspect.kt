package io.bluetape4k.leader.spring.aop

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import io.bluetape4k.leader.annotation.LeaderElection
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.metrics.SkipReason
import io.bluetape4k.leader.spring.aop.cache.FactoryCacheKey
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.leader.spring.aop.util.AnnotationLookup
import io.bluetape4k.leader.spring.aop.util.DurationParser
import io.bluetape4k.leader.spring.aop.util.LockNameValidator
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.beans.factory.SmartInitializingSingleton
import java.lang.reflect.Method
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.toKotlinDuration

/**
 * `@LeaderElection` 어노테이션 처리 Aspect — sync `T?` 및 suspend `T?` 지원.
 *
 * ## [T4.1a Option A] final class @Aspect
 * Boot 4 CTW (Freefair post-compile weaving). autoconfig 가 `@Bean` 으로 등록 — `@Component` 미부착.
 *
 * ## suspend 지원 (#90)
 * - suspend 메서드 감지: 마지막 파라미터가 [Continuation] 인지 확인
 * - `startCoroutineUninterceptedOrReturn` + `suspendCoroutineUninterceptedOrReturn` intrinsics 패턴 사용
 * - [SuspendLeaderElectorFactory] 는 suspend fun — `computeIfAbsent` 불가, "accept rare double-create" 패턴 사용
 * - `SuspendLeaderElector.runIfLeader()` 는 `T?` 반환 — null == 미선출.
 *   선출 후 액션이 null 반환 시 미선출과 구별 불가 (알려진 한계 — v1).
 *
 * ## [Step 3-P] 보강 사항
 * - **Sec-3 (R-33)**: backend 예외만 [LeaderElectionException] 으로 wrapping
 * - **Rel-1**: body 예외 vs backend 예외 분리 — body throw 는 wrapping 없이 그대로 전파
 * - **Rel-2**: [CancellationException] 항상 우선 재throw
 * - **Perf-1**: metrics fan-out 시 `metrics.isEmpty()` fast-path
 * - **Perf-2**: Method-level cache — annotation lookup + lease threshold 사전계산 + factory bean name resolution
 * - **Fix-94**: [resolveLockName] + [factory.create] 를 try 안으로 이동
 *
 * @param beanSelector factory 빈 선택기
 * @param props AOP 전역 속성
 * @param spel SpEL 평가기
 * @param recorders 등록된 metrics recorder 리스트 (0개 = NoOp fast-path)
 */
@Aspect
class LeaderElectionAspect(
    private val beanSelector: LeaderBeanSelector,
    private val props: LeaderAopProperties,
    private val spel: SpelExpressionEvaluator,
    private val lockNameValidator: LockNameValidator,
    private val recorders: List<LeaderAopMetricsRecorder>,
) : SmartInitializingSingleton {

    /** [Step 3-P-Perf-2] Method-level cache — annotation + parsed options + factory lookup. */
    private val metadataCache = ConcurrentHashMap<Method, AdviceMetadata>()

    /** [C1][R-26] FactoryCacheKey 기반 cross-backend collision-safe 캐싱. */
    private val factoryCache = ConcurrentHashMap<FactoryCacheKey, LeaderElector>()

    /** suspend 엘렉터 캐시 — FactoryCacheKey(suspendElectorFactoryBeanName, options). */
    private val suspendElectorCache = ConcurrentHashMap<FactoryCacheKey, SuspendLeaderElector>()

    /** [Perf-1] recorder 0개일 때 fast-path 활성화. */
    private val hasRecorders = recorders.isNotEmpty()

    @Around("@annotation(io.bluetape4k.leader.annotation.LeaderElection)")
    fun aroundLeader(pjp: ProceedingJoinPoint): Any? {
        val method = (pjp.signature as MethodSignature).method
        val target = pjp.target
        val args = pjp.args

        val meta = metadataCache.computeIfAbsent(method) { resolveMetadata(it, target) }

        if (meta.isSuspend) {
            return aroundLeaderSuspend(pjp, meta)
        }

        val opts = meta.options

        // [Fix-94] resolveLockName + factory.create 를 try 안으로 이동
        var lockName: String? = null
        val start = System.nanoTime()

        return try {
            val resolvedName = resolveLockName(meta, method, args, target)
            lockName = resolvedName

            val cacheKey = FactoryCacheKey(meta.factoryBeanName, opts)
            val election = factoryCache.computeIfAbsent(cacheKey) { meta.factory.create(opts) }

            fanOut { it.onLockAttempt(resolvedName, opts) }

            val runResult = election.runIfLeaderResult(resolvedName) {
                fanOut {
                    it.onLockAcquired(resolvedName, opts, (System.nanoTime() - start).nanoseconds)
                    it.onTaskStarted(resolvedName)
                }
                executeBody(pjp, resolvedName, start)
            }
            when (runResult) {
                is LeaderRunResult.Skipped -> {
                    if (meta.failureMode == LeaderAspectFailureMode.FAIL_OPEN_RUN) {
                        fanOut {
                            it.onLockNotAcquired(resolvedName, opts, SkipReason.FAIL_OPEN_FORCED)
                            it.onTaskStarted(resolvedName)
                        }
                        log.debug { "leader.aop.fail-open lockName=$resolvedName reason=CONTENTION" }
                        val result = executeBody(pjp, resolvedName, start)
                        val elapsed = System.nanoTime() - start
                        fanOut { it.onTaskFinished(resolvedName, elapsed.nanoseconds) }
                        result
                    } else {
                        fanOut { it.onLockNotAcquired(resolvedName, opts, SkipReason.CONTENTION) }
                        log.debug { "leader.aop.skipped lockName=$resolvedName reason=CONTENTION" }
                        null
                    }
                }
                is LeaderRunResult.Elected -> {
                    val elapsed = System.nanoTime() - start
                    fanOut { it.onTaskFinished(resolvedName, elapsed.nanoseconds) }
                    log.debug { "leader.aop.elected lockName=$resolvedName elapsedNs=$elapsed" }
                    if (elapsed > meta.leaseTimeWarnThresholdNanos) {
                        log.warn {
                            "leader.aop.lease-warn lockName=$resolvedName elapsedNs=$elapsed leaseTimeNs=${opts.leaseTime.inWholeNanoseconds}"
                        }
                    }
                    runResult.value
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (bodyMarker: BodyThrownMarker) {
            throw bodyMarker.cause
        } catch (backendEx: Throwable) {
            val effectiveName = lockName ?: "<unresolved:${meta.nameExpression}>"
            val wrapped = LeaderElectionException("leader backend error for lock '$effectiveName'", backendEx)
            when (meta.failureMode) {
                LeaderAspectFailureMode.INHERIT -> error("INHERIT must be resolved in resolveMetadata")
                LeaderAspectFailureMode.RETHROW -> {
                    fanOut { it.onLockNotAcquired(effectiveName, opts, SkipReason.BACKEND_ERROR) }
                    fanOut { it.onTaskFailed(effectiveName, (System.nanoTime() - start).nanoseconds, backendEx) }
                    throw wrapped
                }
                LeaderAspectFailureMode.SKIP -> {
                    fanOut { it.onLockNotAcquired(effectiveName, opts, SkipReason.BACKEND_ERROR) }
                    fanOut { it.onTaskFailed(effectiveName, (System.nanoTime() - start).nanoseconds, backendEx) }
                    log.warn(backendEx) { "leader.aop.skipped lockName=$effectiveName reason=BACKEND_ERROR" }
                    null
                }
                LeaderAspectFailureMode.FAIL_OPEN_RUN -> {
                    fanOut { it.onLockNotAcquired(effectiveName, opts, SkipReason.FAIL_OPEN_FORCED) }
                    log.warn(backendEx) { "leader.aop.fail-open lockName=$effectiveName reason=BACKEND_ERROR" }
                    fanOut { it.onTaskStarted(effectiveName) }
                    try {
                        val result = pjp.proceed()
                        val elapsed = System.nanoTime() - start
                        fanOut { it.onTaskFinished(effectiveName, elapsed.nanoseconds) }
                        result
                    } catch (ce: CancellationException) {
                        fanOut { it.onTaskFailed(effectiveName, (System.nanoTime() - start).nanoseconds, ce) }
                        throw ce
                    } catch (bodyEx: Throwable) {
                        fanOut { it.onTaskFailed(effectiveName, (System.nanoTime() - start).nanoseconds, bodyEx) }
                        throw bodyEx
                    }
                }
            }
        }
    }

    /**
     * suspend 메서드 처리 — `startCoroutineUninterceptedOrReturn` intrinsics 패턴.
     *
     * [ProceedingJoinPoint.args] 마지막 원소(Continuation)를 inner continuation 으로 교체하여
     * 리더 선출 로직과 suspend body 를 올바르게 연결한다.
     *
     * ## null 반환 한계 (v1)
     * [SuspendLeaderElector.runIfLeader] 는 `T?` 반환 — null 이 "미선출"인지
     * "액션 결과 null" 인지 구별 불가. null 은 "미선출"로 처리.
     */
    private fun aroundLeaderSuspend(pjp: ProceedingJoinPoint, meta: AdviceMetadata): Any? {
        @Suppress("UNCHECKED_CAST")
        val continuation = pjp.args.last() as Continuation<Any?>
        val start = System.nanoTime()
        val method = (pjp.signature as MethodSignature).method

        val suspendBlock: suspend () -> Any? = {
            var lockName: String? = null
            try {
                val resolvedName = resolveLockName(meta, method, pjp.args, pjp.target)
                lockName = resolvedName
                val cacheKey = FactoryCacheKey(meta.suspendElectorFactoryBeanName, meta.options)
                val elector = suspendElectorCache[cacheKey]
                    ?: meta.suspendElectorFactory!!.create(meta.options)
                        .also { suspendElectorCache.putIfAbsent(cacheKey, it) }

                fanOut { it.onLockAttempt(resolvedName, meta.options) }

                val result = elector.runIfLeader(resolvedName) {
                    fanOut {
                        it.onLockAcquired(resolvedName, meta.options, (System.nanoTime() - start).nanoseconds)
                        it.onTaskStarted(resolvedName)
                    }
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val bodyResult = suspendCoroutineUninterceptedOrReturn<Any?> { innerCont ->
                            val newArgs = pjp.args.copyOf()
                            newArgs[newArgs.lastIndex] = innerCont as Continuation<Any?>
                            pjp.proceed(newArgs)
                        }
                        val elapsed = System.nanoTime() - start
                        fanOut { it.onTaskFinished(resolvedName, elapsed.nanoseconds) }
                        if (elapsed > meta.leaseTimeWarnThresholdNanos) {
                            log.warn { "leader.aop.lease-warn lockName=$resolvedName elapsedNs=$elapsed leaseTimeNs=${meta.options.leaseTime.inWholeNanoseconds}" }
                        }
                        log.debug { "leader.aop.elected lockName=$resolvedName elapsedNs=$elapsed" }
                        bodyResult
                    } catch (ce: CancellationException) {
                        fanOut { it.onTaskFailed(resolvedName, (System.nanoTime() - start).nanoseconds, ce) }
                        throw ce
                    } catch (bodyEx: Throwable) {
                        fanOut { it.onTaskFailed(resolvedName, (System.nanoTime() - start).nanoseconds, bodyEx) }
                        throw BodyThrownMarker(bodyEx)
                    }
                }

                if (result == null) {
                    if (meta.failureMode == LeaderAspectFailureMode.FAIL_OPEN_RUN) {
                        fanOut {
                            it.onLockNotAcquired(resolvedName, meta.options, SkipReason.FAIL_OPEN_FORCED)
                            it.onTaskStarted(resolvedName)
                        }
                        log.debug { "leader.aop.fail-open lockName=$resolvedName reason=CONTENTION" }
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val failOpenResult = suspendCoroutineUninterceptedOrReturn<Any?> { innerCont ->
                                val newArgs = pjp.args.copyOf()
                                newArgs[newArgs.lastIndex] = innerCont as Continuation<Any?>
                                pjp.proceed(newArgs)
                            }
                            fanOut { it.onTaskFinished(resolvedName, (System.nanoTime() - start).nanoseconds) }
                            failOpenResult
                        } catch (ce: CancellationException) {
                            fanOut { it.onTaskFailed(resolvedName, (System.nanoTime() - start).nanoseconds, ce) }
                            throw ce
                        } catch (bodyEx: Throwable) {
                            fanOut { it.onTaskFailed(resolvedName, (System.nanoTime() - start).nanoseconds, bodyEx) }
                            throw bodyEx
                        }
                    } else {
                        fanOut { it.onLockNotAcquired(resolvedName, meta.options, SkipReason.CONTENTION) }
                        log.debug { "leader.aop.skipped lockName=$resolvedName reason=CONTENTION" }
                        null
                    }
                } else {
                    result
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (bm: BodyThrownMarker) {
                throw bm.cause
            } catch (backendEx: Throwable) {
                val effectiveName = lockName ?: "<unresolved:${meta.nameExpression}>"
                val wrapped = LeaderElectionException("leader backend error for lock '$effectiveName'", backendEx)
                when (meta.failureMode) {
                    LeaderAspectFailureMode.INHERIT -> error("INHERIT must be resolved in resolveMetadata")
                    LeaderAspectFailureMode.RETHROW -> {
                        fanOut { it.onLockNotAcquired(effectiveName, meta.options, SkipReason.BACKEND_ERROR) }
                        fanOut { it.onTaskFailed(effectiveName, (System.nanoTime() - start).nanoseconds, backendEx) }
                        throw wrapped
                    }
                    LeaderAspectFailureMode.SKIP -> {
                        fanOut { it.onLockNotAcquired(effectiveName, meta.options, SkipReason.BACKEND_ERROR) }
                        fanOut { it.onTaskFailed(effectiveName, (System.nanoTime() - start).nanoseconds, backendEx) }
                        log.warn(backendEx) { "leader.aop.skipped lockName=$effectiveName reason=BACKEND_ERROR" }
                        null
                    }
                    LeaderAspectFailureMode.FAIL_OPEN_RUN -> {
                        fanOut { it.onLockNotAcquired(effectiveName, meta.options, SkipReason.FAIL_OPEN_FORCED) }
                        log.warn(backendEx) { "leader.aop.fail-open lockName=$effectiveName reason=BACKEND_ERROR" }
                        fanOut { it.onTaskStarted(effectiveName) }
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val failOpenResult = suspendCoroutineUninterceptedOrReturn<Any?> { innerCont ->
                                val newArgs = pjp.args.copyOf()
                                newArgs[newArgs.lastIndex] = innerCont as Continuation<Any?>
                                pjp.proceed(newArgs)
                            }
                            fanOut { it.onTaskFinished(effectiveName, (System.nanoTime() - start).nanoseconds) }
                            failOpenResult
                        } catch (ce: CancellationException) {
                            fanOut { it.onTaskFailed(effectiveName, (System.nanoTime() - start).nanoseconds, ce) }
                            throw ce
                        } catch (bodyEx: Throwable) {
                            fanOut { it.onTaskFailed(effectiveName, (System.nanoTime() - start).nanoseconds, bodyEx) }
                            throw bodyEx
                        }
                    }
                }
            }
        }
        return suspendBlock.startCoroutineUninterceptedOrReturn(continuation)
    }

    /**
     * [Rel-1] body 실행 — 사용자 본문 throw 는 wrapping 없이 그대로 전파.
     * [CancellationException] 우선 재throw [Rel-2].
     */
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

    /** Body throw vs backend throw 구분용 internal marker — outer catch 에서 cause 만 re-throw. */
    private class BodyThrownMarker(override val cause: Throwable) : RuntimeException(cause)

    override fun afterSingletonsInstantiated() {
        // BPP/Validator 가 별도로 검증 — Aspect 는 캐시 워밍 미수행
    }

    private fun resolveLockName(meta: AdviceMetadata, method: Method, args: Array<Any?>, target: Any): String {
        val rawName = if (meta.literalName != null) {
            meta.literalName
        } else {
            spel.evaluate(meta.nameExpression, method, args, target)
        }
        val prefixed = lockNameValidator.applyPrefix(rawName)
        lockNameValidator.validate(prefixed)
        return prefixed
    }

    private fun resolveMetadata(method: Method, target: Any): AdviceMetadata {
        val ann = AnnotationLookup.findAnnotationWithTargetFallback(method, target, LeaderElection::class.java)
            ?: error("@LeaderElection not found on ${method.declaringClass.name}#${method.name}")

        val waitTime = DurationParser.parseOrDefault(ann.waitTime, props.defaultWaitTime).toKotlinDuration()
        val leaseTime = DurationParser.parseOrDefault(ann.leaseTime, props.defaultLeaseTime).toKotlinDuration()

        val opts = LeaderElectionOptions(waitTime = waitTime, leaseTime = leaseTime)
        val selected = beanSelector.selectElectionFactory(ann.bean, method)
        val literal = if (LITERAL_PATTERN.matches(ann.name)) ann.name else null

        val effectiveFailureMode = if (ann.failureMode == LeaderAspectFailureMode.INHERIT) {
            props.failureMode
        } else {
            ann.failureMode
        }

        val isSuspend = method.parameterTypes.lastOrNull() == Continuation::class.java
        val (suspendElectorFactory, suspendElectorFactoryBeanName) = if (isSuspend) {
            val suspendSelected = beanSelector.selectSuspendElectorFactory(ann.bean, method)
            suspendSelected.bean to suspendSelected.beanName
        } else {
            null to ""
        }

        return AdviceMetadata(
            nameExpression = ann.name,
            literalName = literal,
            options = opts,
            factoryBeanName = selected.beanName,
            factory = selected.bean,
            failureMode = effectiveFailureMode,
            leaseTimeWarnThresholdNanos = (leaseTime.inWholeNanoseconds * LEASE_WARN_RATIO).toLong(),
            isSuspend = isSuspend,
            suspendElectorFactory = suspendElectorFactory,
            suspendElectorFactoryBeanName = suspendElectorFactoryBeanName,
        )
    }

    private inline fun fanOut(crossinline action: (LeaderAopMetricsRecorder) -> Unit) {
        if (!hasRecorders) return
        for (recorder in recorders) {
            runCatching { action(recorder) }
                .onFailure { log.warn(it) { "metrics recorder threw" } }
        }
    }

    private data class AdviceMetadata(
        val nameExpression: String,
        val literalName: String?,
        val options: LeaderElectionOptions,
        val factoryBeanName: String,
        val factory: LeaderElectorFactory,
        val failureMode: LeaderAspectFailureMode,
        val leaseTimeWarnThresholdNanos: Long,
        val isSuspend: Boolean,
        val suspendElectorFactory: SuspendLeaderElectorFactory?,
        val suspendElectorFactoryBeanName: String,
    )

    companion object: KLogging() {
        private val LITERAL_PATTERN = Regex("^[A-Za-z0-9_:.\\-]+$")
        private const val LEASE_WARN_RATIO = 0.8
    }
}
