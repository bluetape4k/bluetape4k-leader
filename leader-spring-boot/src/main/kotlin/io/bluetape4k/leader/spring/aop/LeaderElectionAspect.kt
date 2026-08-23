package io.bluetape4k.leader.spring.aop

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import io.bluetape4k.leader.annotation.LeaderElection
import io.bluetape4k.leader.coroutines.LeaderElectionInfo
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.metrics.SkipReason
import io.bluetape4k.leader.spring.aop.cache.FactoryCacheKey
import io.bluetape4k.leader.spring.aop.internal.AdviceBranch
import io.bluetape4k.leader.spring.aop.internal.AdviceMetadata
import io.bluetape4k.leader.spring.aop.internal.BodyThrownMarker
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.leader.spring.aop.util.AnnotationLookup
import io.bluetape4k.leader.spring.aop.util.DurationParser
import io.bluetape4k.leader.spring.aop.util.LockNameValidator
import io.bluetape4k.leader.spring.scheduling.LeaderScheduledPolicyRegistry
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.flux
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.SmartInitializingSingleton
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.lang.reflect.Method
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.toKotlinDuration

/**
 * `LeaderElectionAspect`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property beanSelector Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property props Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property spel Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property lockNameValidator Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property recorders Spring Boot integration 계약에서 사용하는 속성입니다.
 */
@Aspect
@Suppress("LargeClass", "TooManyFunctions")
class LeaderElectionAspect(
    private val beanSelector: LeaderBeanSelector,
    private val props: LeaderAopProperties,
    private val spel: SpelExpressionEvaluator,
    private val lockNameValidator: LockNameValidator,
    private val recorders: List<LeaderAopMetricsRecorder>,
    private val scheduledPolicyRegistry: LeaderScheduledPolicyRegistry? = null,
) : SmartInitializingSingleton, DisposableBean {

    /** JVM/source compatibility constructor retained for existing direct users and tests. */
    constructor(
        beanSelector: LeaderBeanSelector,
        props: LeaderAopProperties,
        spel: SpelExpressionEvaluator,
        lockNameValidator: LockNameValidator,
        recorders: List<LeaderAopMetricsRecorder>,
    ) : this(beanSelector, props, spel, lockNameValidator, recorders, null)

    private val metadataCache = ConcurrentHashMap<TargetMethodCacheKey, MetadataResolution>()
    private val factoryCache = ConcurrentHashMap<FactoryCacheKey, LeaderElector>()
    private val suspendElectorCache = ConcurrentHashMap<FactoryCacheKey, SuspendLeaderElector>()
    private val hasRecorders = recorders.isNotEmpty()

    @Around(
        "execution(* *(..)) && (" +
            "@annotation(io.bluetape4k.leader.annotation.LeaderElection) || " +
            "@annotation(io.bluetape4k.leader.spring.scheduling.LeaderScheduled) || " +
            "@annotation(org.springframework.scheduling.annotation.Scheduled))"
    )
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount", "ThrowsCount")
    fun aroundLeader(pjp: ProceedingJoinPoint): Any? {
        val method = (pjp.signature as MethodSignature).method
        val target = pjp.target
        val args = pjp.args

        if (method.returnType.name == FLUX_RETURN_TYPE) {
            return Flux.defer<Any> {
                when (val resolution = resolveMetadata(method, target)) {
                    MetadataResolution.Bypass -> {
                        @Suppress("UNCHECKED_CAST")
                        pjp.proceed() as Flux<Any>
                    }
                    is MetadataResolution.Present -> aroundLeaderFlux(pjp, resolution.metadata) as Flux<Any>
                }
            }
        }

        if (method.returnType.name == FLOW_RETURN_TYPE) {
            return when (val resolution = resolveMetadata(method, target)) {
                MetadataResolution.Bypass -> {
                    @Suppress("UNCHECKED_CAST")
                    pjp.proceed() as Flow<Any?>
                }
                is MetadataResolution.Present -> aroundLeaderFlow(pjp, method, resolution.metadata)
            }
        }

        if (method.returnType.name == MONO_RETURN_TYPE) {
            return Mono.defer<Any> {
                when (val resolution = resolveMetadata(method, target)) {
                    MetadataResolution.Bypass -> {
                        @Suppress("UNCHECKED_CAST")
                        pjp.proceed() as Mono<Any>
                    }
                    is MetadataResolution.Present -> aroundLeaderMono(pjp, resolution.metadata) as Mono<Any>
                }
            }
        }

        val resolution = resolveMetadata(method, target)
        if (resolution === MetadataResolution.Bypass) return pjp.proceed()
        val meta = (resolution as MetadataResolution.Present).metadata

        if (meta.isSuspend) {
            return aroundLeaderSuspend(pjp, meta)
        }

        val opts = meta.options
        var lockName: String? = null
        var resolvedIdentity: LockIdentity? = null
        val start = System.nanoTime()

        fun resolveIdentity(name: String, branch: AdviceBranch): LockIdentity =
            resolvedIdentity ?: meta.resolveLockIdentity(name, branch).also { resolvedIdentity = it }

        return try {
            val resolvedName = resolveLockName(meta, method, args, target)
            lockName = resolvedName

            // ── Reentrant short-circuit (T14 + Tier 7 P1-1): full LockIdentity 매칭 시에만 short-circuit ──
            //   동일 lockName + 다른 annotation kind (SINGLE vs GROUP) 또는 다른 groupParams 는 별개 lock — 새 acquire.
            val identity = resolveIdentity(resolvedName, AdviceBranch.SYNC)
            val existing = AopScopeAccess.peekSyncMatching(resolvedName)
            if (existing is LeaderLockHandle.Real && existing.matchesIdentity(identity)) {
                log.debug { "leader.aop.reentrant lockName=$resolvedName depth=${existing.reentryDepth + 1}" }
                val reentrantHandle = AopScopeAccess.incrementReentryDepth(existing)
                return AopScopeAccess.withPushedSync(reentrantHandle) {
                    executeBody(pjp, resolvedName, start)
                }
            }

            val cacheKey = FactoryCacheKey(meta.factoryBeanName, opts)
            val election = factoryCache.computeIfAbsent(cacheKey) { meta.factory.create(opts) }

            fanOut { it.onLockAttempt(resolvedName, opts) }

            val runResult = election.runIfLeaderResult(resolvedName) {
                // The elector (e.g., AbstractLocalLeaderElector) already calls
                // LockStateHolder.withPushed(handle) { action() } internally.
                // We do NOT double-push here — the elector manages the sync stack.
                fanOut {
                    it.onLockAcquired(resolvedName, opts, (System.nanoTime() - start).nanoseconds)
                    it.onTaskStarted(resolvedName)
                }
                executeBody(pjp, resolvedName, start)
            }
            when (runResult) {
                is LeaderRunResult.Skipped -> {
                    if (meta.failureMode == LeaderAspectFailureMode.FAIL_OPEN_RUN) {
                        val failOpenHandle = AopScopeAccess.createFailOpen(identity)
                        fanOut {
                            it.onLockNotAcquired(resolvedName, opts, SkipReason.FAIL_OPEN_FORCED)
                            it.onTaskStarted(resolvedName)
                        }
                        log.debug { "leader.aop.fail-open lockName=$resolvedName reason=CONTENTION" }
                        val result = AopScopeAccess.withPushedSync(failOpenHandle) {
                            executeBody(pjp, resolvedName, start)
                        }
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
                is LeaderRunResult.ActionFailed -> throw BodyThrownMarker(runResult.cause)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (bodyMarker: BodyThrownMarker) {
            throw bodyMarker.cause
        } catch (backendEx: Exception) {
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
                    val opts2 = meta.options
                    fanOut { it.onLockNotAcquired(effectiveName, opts2, SkipReason.FAIL_OPEN_FORCED) }
                    log.warn(backendEx) { "leader.aop.fail-open lockName=$effectiveName reason=BACKEND_ERROR" }
                    fanOut { it.onTaskStarted(effectiveName) }
                    val failOpenHandle = AopScopeAccess.createFailOpen(
                        resolveIdentity(effectiveName, AdviceBranch.SYNC)
                    )
                    try {
                        val result = AopScopeAccess.withPushedSync(failOpenHandle) {
                            pjp.proceed()
                        }
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
     * `aroundLeaderSuspend` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    private fun aroundLeaderSuspend(pjp: ProceedingJoinPoint, meta: AdviceMetadata): Any? {
        @Suppress("UNCHECKED_CAST")
        val continuation = pjp.args.last() as Continuation<Any?>
        val start = System.nanoTime()
        val method = (pjp.signature as MethodSignature).method

        val suspendBlock: suspend () -> Any? = {
            var lockName: String? = null
            var resolvedIdentity: LockIdentity? = null

            fun resolveIdentity(name: String): LockIdentity =
                resolvedIdentity ?: meta.resolveLockIdentity(name, AdviceBranch.COROUTINES)
                    .also { resolvedIdentity = it }

            try {
                val resolvedName = resolveLockName(meta, method, pjp.args, pjp.target)
                lockName = resolvedName
                val cacheKey = FactoryCacheKey(meta.suspendElectorFactoryBeanName, meta.options)
                // Tier 5 C1 — `!!` 제거. SuspendLeaderElectorFactory.create 는 suspend 이므로 computeIfAbsent 사용 불가.
                // 첫 miss 동시 호출 시 패배자 elector 인스턴스 GC — SuspendLeaderElector 에 close() 없어 자원 leak 없음.
                val factory = checkNotNull(meta.suspendElectorFactory) {
                    "suspendElectorFactory must be non-null in COROUTINES/REACTIVE branch (branch=${meta.branch})"
                }
                val elector = suspendElectorCache[cacheKey]
                    ?: factory.create(meta.options).also { suspendElectorCache.putIfAbsent(cacheKey, it) }

                fanOut { it.onLockAttempt(resolvedName, meta.options) }

                val result = elector.runIfLeader(resolvedName) {
                    fanOut {
                        it.onLockAcquired(resolvedName, meta.options, (System.nanoTime() - start).nanoseconds)
                        it.onTaskStarted(resolvedName)
                    }
                    withContext(LeaderElectionInfo(lockName = resolvedName, wasElected = true)) {
                        try {
                            @Suppress("UNCHECKED_CAST")
                                val bodyResult = suspendCoroutineUninterceptedOrReturn<Any?> { innerCont ->
                                    val newArgs = pjp.args.copyOf()
                                    newArgs[newArgs.lastIndex] = innerCont
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
                }

                if (result == null) {
                    if (meta.failureMode == LeaderAspectFailureMode.FAIL_OPEN_RUN) {
                        val failOpenHandle = AopScopeAccess.createFailOpen(resolveIdentity(resolvedName))
                        fanOut {
                            it.onLockNotAcquired(resolvedName, meta.options, SkipReason.FAIL_OPEN_FORCED)
                            it.onTaskStarted(resolvedName)
                        }
                        log.debug { "leader.aop.fail-open lockName=$resolvedName reason=CONTENTION" }
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val failOpenResult = withContext(
                                LeaderElectionInfo(lockName = resolvedName, wasElected = false) +
                                    AopScopeAccess.createLockHandleElement(failOpenHandle)
                            ) {
                                suspendCoroutineUninterceptedOrReturn<Any?> { innerCont ->
                                    val newArgs = pjp.args.copyOf()
                                    newArgs[newArgs.lastIndex] = innerCont
                                    pjp.proceed(newArgs)
                                }
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
            } catch (backendEx: Exception) {
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
                        val failOpenHandle = AopScopeAccess.createFailOpen(resolveIdentity(effectiveName))
                        fanOut { it.onLockNotAcquired(effectiveName, meta.options, SkipReason.FAIL_OPEN_FORCED) }
                        log.warn(backendEx) { "leader.aop.fail-open lockName=$effectiveName reason=BACKEND_ERROR" }
                        fanOut { it.onTaskStarted(effectiveName) }
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val failOpenResult = withContext(
                                LeaderElectionInfo(lockName = effectiveName, wasElected = false) +
                                    AopScopeAccess.createLockHandleElement(failOpenHandle)
                            ) {
                                suspendCoroutineUninterceptedOrReturn<Any?> { innerCont ->
                                    val newArgs = pjp.args.copyOf()
                                    newArgs[newArgs.lastIndex] = innerCont
                                    pjp.proceed(newArgs)
                                }
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
     * `aroundLeaderFlux` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    private fun aroundLeaderFlux(pjp: ProceedingJoinPoint, meta: AdviceMetadata): Any? {
        val method = (pjp.signature as MethodSignature).method

        return Flux.defer {
            if (!meta.isStreamAllowed()) {
                return@defer Flux.error(streamConfigurationException(method, meta, "Flux"))
            }

            flux<Any> {
                val start = System.nanoTime()
                var lockName: String? = null
                var resolvedIdentity: LockIdentity? = null

                fun resolveIdentity(name: String): LockIdentity =
                    resolvedIdentity ?: meta.resolveLockIdentity(name, AdviceBranch.REACTIVE)
                        .also { resolvedIdentity = it }

                try {
                    val resolvedName = resolveLockName(meta, method, pjp.args, pjp.target)
                    lockName = resolvedName
                    val cacheKey = FactoryCacheKey(meta.suspendElectorFactoryBeanName, meta.options)
                    val factory = checkNotNull(meta.suspendElectorFactory) {
                        "suspendElectorFactory must be non-null in REACTIVE branch (Flux)"
                    }
                    val elector = suspendElectorCache[cacheKey]
                        ?: factory.create(meta.options).also { suspendElectorCache.putIfAbsent(cacheKey, it) }

                    fanOut { it.onLockAttempt(resolvedName, meta.options) }

                    val result = elector.runIfLeaderResultSuspend(resolvedName) {
                        fanOut {
                            it.onLockAcquired(resolvedName, meta.options, (System.nanoTime() - start).nanoseconds)
                            it.onTaskStarted(resolvedName)
                        }
                        withContext(LeaderElectionInfo(lockName = resolvedName, wasElected = true)) {
                            try {
                                @Suppress("UNCHECKED_CAST")
                                val upstream = pjp.proceed() as Flux<Any>
                                upstream.asFlow().collect { send(it) }
                                val elapsed = System.nanoTime() - start
                                fanOut { it.onTaskFinished(resolvedName, elapsed.nanoseconds) }
                                if (elapsed > meta.leaseTimeWarnThresholdNanos) {
                                    log.warn { "leader.aop.lease-warn lockName=$resolvedName elapsedNs=$elapsed leaseTimeNs=${meta.options.leaseTime.inWholeNanoseconds}" }
                                }
                                log.debug { "leader.aop.elected lockName=$resolvedName elapsedNs=$elapsed" }
                            } catch (ce: CancellationException) {
                                fanOut { it.onTaskFailed(resolvedName, (System.nanoTime() - start).nanoseconds, ce) }
                                throw ce
                            } catch (bodyEx: Throwable) {
                                fanOut { it.onTaskFailed(resolvedName, (System.nanoTime() - start).nanoseconds, bodyEx) }
                                throw BodyThrownMarker(bodyEx)
                            }
                        }
                    }

                    when (result) {
                        is LeaderRunResult.Elected -> Unit
                        is LeaderRunResult.Skipped -> {
                            if (meta.failureMode == LeaderAspectFailureMode.FAIL_OPEN_RUN) {
                                val failOpenHandle = AopScopeAccess.createFailOpen(resolveIdentity(resolvedName))
                                fanOut {
                                    it.onLockNotAcquired(resolvedName, meta.options, SkipReason.FAIL_OPEN_FORCED)
                                    it.onTaskStarted(resolvedName)
                                }
                                log.debug { "leader.aop.fail-open lockName=$resolvedName reason=CONTENTION" }
                                try {
                                    withContext(
                                        LeaderElectionInfo(lockName = resolvedName, wasElected = false) +
                                            AopScopeAccess.createLockHandleElement(failOpenHandle)
                                    ) {
                                        @Suppress("UNCHECKED_CAST")
                                        val upstream = pjp.proceed() as Flux<Any>
                                        upstream.asFlow().collect { send(it) }
                                    }
                                    fanOut { it.onTaskFinished(resolvedName, (System.nanoTime() - start).nanoseconds) }
                                } catch (ce: CancellationException) {
                                    fanOut { it.onTaskFailed(resolvedName, (System.nanoTime() - start).nanoseconds, ce) }
                                    throw ce
                                } catch (bodyEx: Throwable) {
                                    fanOut { it.onTaskFailed(resolvedName, (System.nanoTime() - start).nanoseconds, bodyEx) }
                                    throw BodyThrownMarker(bodyEx)
                                }
                            } else {
                                fanOut { it.onLockNotAcquired(resolvedName, meta.options, SkipReason.CONTENTION) }
                                log.debug { "leader.aop.skipped lockName=$resolvedName reason=CONTENTION" }
                            }
                        }
                        is LeaderRunResult.ActionFailed -> throw result.cause
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (bm: BodyThrownMarker) {
                    throw bm.cause
                } catch (backendEx: Exception) {
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
                        }
                        LeaderAspectFailureMode.FAIL_OPEN_RUN -> {
                            val failOpenHandle = AopScopeAccess.createFailOpen(
                                meta.resolveLockIdentity(effectiveName, AdviceBranch.REACTIVE)
                            )
                            fanOut { it.onLockNotAcquired(effectiveName, meta.options, SkipReason.FAIL_OPEN_FORCED) }
                            log.warn(backendEx) { "leader.aop.fail-open lockName=$effectiveName reason=BACKEND_ERROR" }
                            fanOut { it.onTaskStarted(effectiveName) }
                            try {
                                withContext(
                                    LeaderElectionInfo(lockName = effectiveName, wasElected = false) +
                                        AopScopeAccess.createLockHandleElement(failOpenHandle)
                                ) {
                                    @Suppress("UNCHECKED_CAST")
                                    val upstream = pjp.proceed() as Flux<Any>
                                    upstream.asFlow().collect { send(it) }
                                }
                                fanOut { it.onTaskFinished(effectiveName, (System.nanoTime() - start).nanoseconds) }
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
        }
    }

    /**
     * `aroundLeaderFlow` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    private fun aroundLeaderFlow(
        pjp: ProceedingJoinPoint,
        method: Method,
        meta: AdviceMetadata,
    ): Flow<Any?> =
        channelFlow {
            if (!meta.isStreamAllowed()) {
                throw streamConfigurationException(method, meta, "Flow")
            }

            val start = System.nanoTime()
            var lockName: String? = null
            var resolvedIdentity: LockIdentity? = null

            fun resolveIdentity(name: String): LockIdentity =
                resolvedIdentity ?: meta.resolveLockIdentity(name, AdviceBranch.COROUTINES)
                    .also { resolvedIdentity = it }

            try {
                val resolvedName = resolveLockName(meta, method, pjp.args, pjp.target)
                lockName = resolvedName
                val cacheKey = FactoryCacheKey(meta.suspendElectorFactoryBeanName, meta.options)
                val factory = checkNotNull(meta.suspendElectorFactory) {
                    "suspendElectorFactory must be non-null in COROUTINES branch (Flow)"
                }
                val elector = suspendElectorCache[cacheKey]
                    ?: factory.create(meta.options).also { suspendElectorCache.putIfAbsent(cacheKey, it) }

                fanOut { it.onLockAttempt(resolvedName, meta.options) }

                val result = elector.runIfLeaderResultSuspend(resolvedName) {
                    fanOut {
                        it.onLockAcquired(resolvedName, meta.options, (System.nanoTime() - start).nanoseconds)
                        it.onTaskStarted(resolvedName)
                    }
                    withContext(LeaderElectionInfo(lockName = resolvedName, wasElected = true)) {
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val upstream = pjp.proceed() as Flow<Any?>
                            upstream.collect { send(it) }
                            val elapsed = System.nanoTime() - start
                            fanOut { it.onTaskFinished(resolvedName, elapsed.nanoseconds) }
                            if (elapsed > meta.leaseTimeWarnThresholdNanos) {
                                log.warn { "leader.aop.lease-warn lockName=$resolvedName elapsedNs=$elapsed leaseTimeNs=${meta.options.leaseTime.inWholeNanoseconds}" }
                            }
                            log.debug { "leader.aop.elected lockName=$resolvedName elapsedNs=$elapsed" }
                        } catch (ce: CancellationException) {
                            fanOut { it.onTaskFailed(resolvedName, (System.nanoTime() - start).nanoseconds, ce) }
                            throw ce
                        } catch (bodyEx: Throwable) {
                            fanOut { it.onTaskFailed(resolvedName, (System.nanoTime() - start).nanoseconds, bodyEx) }
                            throw BodyThrownMarker(bodyEx)
                        }
                    }
                }

                when (result) {
                    is LeaderRunResult.Elected -> Unit
                    is LeaderRunResult.Skipped -> {
                        if (meta.failureMode == LeaderAspectFailureMode.FAIL_OPEN_RUN) {
                            val failOpenHandle = AopScopeAccess.createFailOpen(resolveIdentity(resolvedName))
                            fanOut {
                                it.onLockNotAcquired(resolvedName, meta.options, SkipReason.FAIL_OPEN_FORCED)
                                it.onTaskStarted(resolvedName)
                            }
                            log.debug { "leader.aop.fail-open lockName=$resolvedName reason=CONTENTION" }
                            try {
                                withContext(
                                    LeaderElectionInfo(lockName = resolvedName, wasElected = false) +
                                        AopScopeAccess.createLockHandleElement(failOpenHandle)
                                ) {
                                    @Suppress("UNCHECKED_CAST")
                                    val upstream = pjp.proceed() as Flow<Any?>
                                    upstream.collect { send(it) }
                                }
                                fanOut { it.onTaskFinished(resolvedName, (System.nanoTime() - start).nanoseconds) }
                            } catch (ce: CancellationException) {
                                fanOut { it.onTaskFailed(resolvedName, (System.nanoTime() - start).nanoseconds, ce) }
                                throw ce
                            } catch (bodyEx: Throwable) {
                                fanOut { it.onTaskFailed(resolvedName, (System.nanoTime() - start).nanoseconds, bodyEx) }
                                throw BodyThrownMarker(bodyEx)
                            }
                        } else {
                            fanOut { it.onLockNotAcquired(resolvedName, meta.options, SkipReason.CONTENTION) }
                            log.debug { "leader.aop.skipped lockName=$resolvedName reason=CONTENTION" }
                        }
                    }
                    is LeaderRunResult.ActionFailed -> throw result.cause
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (bm: BodyThrownMarker) {
                throw bm.cause
            } catch (backendEx: Exception) {
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
                    }
                    LeaderAspectFailureMode.FAIL_OPEN_RUN -> {
                        val failOpenHandle = AopScopeAccess.createFailOpen(
                            meta.resolveLockIdentity(effectiveName, AdviceBranch.COROUTINES)
                        )
                        fanOut { it.onLockNotAcquired(effectiveName, meta.options, SkipReason.FAIL_OPEN_FORCED) }
                        log.warn(backendEx) { "leader.aop.fail-open lockName=$effectiveName reason=BACKEND_ERROR" }
                        fanOut { it.onTaskStarted(effectiveName) }
                        try {
                            withContext(
                                LeaderElectionInfo(lockName = effectiveName, wasElected = false) +
                                    AopScopeAccess.createLockHandleElement(failOpenHandle)
                            ) {
                                @Suppress("UNCHECKED_CAST")
                                val upstream = pjp.proceed() as Flow<Any?>
                                upstream.collect { send(it) }
                            }
                            fanOut { it.onTaskFinished(effectiveName, (System.nanoTime() - start).nanoseconds) }
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
        }.buffer(Channel.RENDEZVOUS)

    /**
     * `aroundLeaderMono` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    private fun aroundLeaderMono(pjp: ProceedingJoinPoint, meta: AdviceMetadata): Any? {
        val method = (pjp.signature as MethodSignature).method

        return Mono.defer {
            val start = System.nanoTime()
            mono {
                var lockName: String? = null
                var resolvedIdentity: LockIdentity? = null

                fun resolveIdentity(name: String): LockIdentity =
                    resolvedIdentity ?: meta.resolveLockIdentity(name, AdviceBranch.REACTIVE)
                        .also { resolvedIdentity = it }

                try {
                    val resolvedName = resolveLockName(meta, method, pjp.args, pjp.target)
                    lockName = resolvedName
                    val cacheKey = FactoryCacheKey(meta.suspendElectorFactoryBeanName, meta.options)
                    val factory = checkNotNull(meta.suspendElectorFactory) {
                        "suspendElectorFactory must be non-null in REACTIVE branch (Mono)"
                    }
                    val elector = suspendElectorCache[cacheKey]
                        ?: factory.create(meta.options).also { suspendElectorCache.putIfAbsent(cacheKey, it) }

                    fanOut { it.onLockAttempt(resolvedName, meta.options) }

                    val result = elector.runIfLeader(resolvedName) {
                        fanOut {
                            it.onLockAcquired(resolvedName, meta.options, (System.nanoTime() - start).nanoseconds)
                            it.onTaskStarted(resolvedName)
                        }
                        withContext(LeaderElectionInfo(lockName = resolvedName, wasElected = true)) {
                            try {
                                @Suppress("UNCHECKED_CAST")
                                val bodyResult = (pjp.proceed() as Mono<*>).awaitSingleOrNull()
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
                    }

                    if (result == null) {
                        if (meta.failureMode == LeaderAspectFailureMode.FAIL_OPEN_RUN) {
                            val failOpenHandle = AopScopeAccess.createFailOpen(resolveIdentity(resolvedName))
                            fanOut {
                                it.onLockNotAcquired(resolvedName, meta.options, SkipReason.FAIL_OPEN_FORCED)
                                it.onTaskStarted(resolvedName)
                            }
                            log.debug { "leader.aop.fail-open lockName=$resolvedName reason=CONTENTION" }
                            @Suppress("UNCHECKED_CAST")
                            val failOpenResult = withContext(
                                LeaderElectionInfo(lockName = resolvedName, wasElected = false) +
                                    AopScopeAccess.createLockHandleElement(failOpenHandle)
                            ) {
                                (pjp.proceed() as Mono<*>).awaitSingleOrNull()
                            }
                            fanOut { it.onTaskFinished(resolvedName, (System.nanoTime() - start).nanoseconds) }
                            failOpenResult
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
                } catch (backendEx: Exception) {
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
                            val failOpenHandle = AopScopeAccess.createFailOpen(resolveIdentity(effectiveName))
                            fanOut { it.onLockNotAcquired(effectiveName, meta.options, SkipReason.FAIL_OPEN_FORCED) }
                            log.warn(backendEx) { "leader.aop.fail-open lockName=$effectiveName reason=BACKEND_ERROR" }
                            fanOut { it.onTaskStarted(effectiveName) }
                            try {
                                @Suppress("UNCHECKED_CAST")
                                val failOpenResult = withContext(
                                    LeaderElectionInfo(lockName = effectiveName, wasElected = false) +
                                        AopScopeAccess.createLockHandleElement(failOpenHandle)
                                ) {
                                    (pjp.proceed() as Mono<*>).awaitSingleOrNull()
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

    override fun afterSingletonsInstantiated() {}

    override fun destroy() {
        metadataCache.clear()
        factoryCache.clear()
        suspendElectorCache.clear()
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

    private fun resolveMetadata(method: Method, target: Any): MetadataResolution =
        metadataCache.computeIfAbsent(TargetMethodCacheKey(target, method)) {
            val ann = AnnotationLookup.findAnnotationWithTargetFallback<LeaderElection>(method, target)
            if (ann != null) {
                MetadataResolution.Present(
                    buildMetadata(
                        method = method,
                        nameExpression = ann.name,
                        waitTime = DurationParser.parseOrDefault(
                            ann.waitTime,
                            props.defaultWaitTime,
                        ).toKotlinDuration(),
                        leaseTime = DurationParser.parseOrDefault(
                            ann.leaseTime,
                            props.defaultLeaseTime,
                        ).toKotlinDuration(),
                        minLeaseTime = DurationParser.parseNonNegativeOrDefault(
                            ann.minLeaseTime,
                            java.time.Duration.ZERO,
                        ).toKotlinDuration(),
                        autoExtend = ann.autoExtend,
                        streamBounded = ann.streamBounded,
                        bean = ann.bean,
                        failureMode = ann.failureMode,
                    ),
                )
            } else {
                val policy = scheduledPolicyRegistry?.lookup(method, target)
                if (policy == null) {
                    MetadataResolution.Bypass
                } else {
                    MetadataResolution.Present(
                        buildMetadata(
                            method = method,
                            nameExpression = policy.name,
                            waitTime = (policy.waitTime ?: props.defaultWaitTime).toKotlinDuration(),
                            leaseTime = (policy.leaseTime ?: props.defaultLeaseTime).toKotlinDuration(),
                            minLeaseTime = policy.minLeaseTime.toKotlinDuration(),
                            autoExtend = policy.autoExtend,
                            streamBounded = policy.streamBounded,
                            bean = policy.bean,
                            failureMode = policy.failureMode,
                        ),
                    )
                }
            }
        }

    @Suppress("CyclomaticComplexMethod")
    private fun buildMetadata(
        method: Method,
        nameExpression: String,
        waitTime: kotlin.time.Duration,
        leaseTime: kotlin.time.Duration,
        minLeaseTime: kotlin.time.Duration,
        autoExtend: Boolean,
        streamBounded: Boolean,
        bean: String,
        failureMode: LeaderAspectFailureMode,
    ): AdviceMetadata {
        val opts = LeaderElectionOptions(
            waitTime = waitTime,
            leaseTime = leaseTime,
            minLeaseTime = minLeaseTime,
            autoExtend = autoExtend,
        )
        val selected = beanSelector.selectElectionFactory(bean, method)
        val literal = if (LITERAL_PATTERN.matches(nameExpression)) nameExpression else null

        val effectiveFailureMode = if (failureMode == LeaderAspectFailureMode.INHERIT) {
            props.failureMode
        } else {
            failureMode
        }

        val returnTypeName = method.returnType.name
        val isSuspend = method.parameterTypes.lastOrNull() == Continuation::class.java
        val isMono = !isSuspend && returnTypeName == MONO_RETURN_TYPE
        val isFlux = !isSuspend && returnTypeName == FLUX_RETURN_TYPE
        val isFlow = !isSuspend && returnTypeName == FLOW_RETURN_TYPE
        val branch = when {
            isSuspend || isFlow -> AdviceBranch.COROUTINES
            isMono || isFlux -> AdviceBranch.REACTIVE
            else -> AdviceBranch.SYNC
        }

        val (suspendElectorFactory, suspendElectorFactoryBeanName) = if (isSuspend || isMono || isFlux || isFlow) {
            val suspendSelected = beanSelector.selectSuspendElectorFactory(bean, method)
            suspendSelected.bean to suspendSelected.beanName
        } else {
            null to ""
        }

        return AdviceMetadata(
            nameExpression = nameExpression,
            literalName = literal,
            options = opts,
            factoryBeanName = selected.beanName,
            factory = selected.bean,
            failureMode = effectiveFailureMode,
            leaseTimeWarnThresholdNanos = (leaseTime.inWholeNanoseconds * LEASE_WARN_RATIO).toLong(),
            branch = branch,
            isSuspend = isSuspend,
            isMono = isMono,
            isFlux = isFlux,
            isFlow = isFlow,
            streamBounded = streamBounded,
            suspendElectorFactory = suspendElectorFactory,
            suspendElectorFactoryBeanName = suspendElectorFactoryBeanName,
            annotationKind = LockIdentity.AnnotationKind.SINGLE,
            groupParams = null,
        )
    }

    private sealed interface MetadataResolution {
        data class Present(val metadata: AdviceMetadata) : MetadataResolution

        data object Bypass : MetadataResolution
    }

    private class TargetMethodCacheKey(
        private val target: Any,
        private val method: Method,
    ) {
        override fun equals(other: Any?): Boolean =
            other is TargetMethodCacheKey && target === other.target && method == other.method

        override fun hashCode(): Int = 31 * System.identityHashCode(target) + method.hashCode()
    }

    private fun AdviceMetadata.isStreamAllowed(): Boolean =
        !(isFlux || isFlow) || options.autoExtend || streamBounded

    private fun streamConfigurationException(
        method: Method,
        meta: AdviceMetadata,
        returnShape: String,
    ): LeaderElectionException =
        LeaderElectionException(
            "@LeaderElection $returnShape stream requires autoExtend=true or streamBounded=true: " +
                "${method.declaringClass.name}#${method.name} name='${meta.nameExpression}'",
        )

    private inline fun fanOut(crossinline action: (LeaderAopMetricsRecorder) -> Unit) {
        if (!hasRecorders) return
        for (recorder in recorders) {
            runCatching { action(recorder) }
                .onFailure { log.warn(it) { "metrics recorder threw" } }
        }
    }

    companion object : KLogging() {
        private val LITERAL_PATTERN = Regex("^[A-Za-z0-9_:.\\-]+$")
        private const val LEASE_WARN_RATIO = 0.8
        private const val MONO_RETURN_TYPE = "reactor.core.publisher.Mono"
        private const val FLUX_RETURN_TYPE = "reactor.core.publisher.Flux"
        private const val FLOW_RETURN_TYPE = "kotlinx.coroutines.flow.Flow"
    }
}
