package io.bluetape4k.leader.spring.aop

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupElectionException
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import io.bluetape4k.leader.annotation.LeaderGroupElection
import io.bluetape4k.leader.coroutines.LeaderElectionInfo
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.metrics.SkipReason
import io.bluetape4k.leader.spring.aop.cache.GroupFactoryCacheKey
import io.bluetape4k.leader.spring.aop.internal.AdviceBranch
import io.bluetape4k.leader.spring.aop.internal.BodyThrownMarker
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.leader.spring.aop.util.AnnotationLookup
import io.bluetape4k.leader.spring.aop.util.DurationParser
import io.bluetape4k.leader.spring.aop.util.LockNameValidator
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireGe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.withContext
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.beans.factory.SmartInitializingSingleton
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.toKotlinDuration

/**
 * Aspect that applies `@LeaderGroupElection` to sync `T?`, suspend `T?`, and `Mono<T>` methods.
 * `Flux<T>` and `Flow<T>` return types are rejected until group lease extension semantics are defined.
 *
 * The implementation mirrors [LeaderElectionAspect] and adds the `maxLeaders` option. Backend
 * failures are wrapped in [LeaderGroupElectionException].
 *
 * ## Suspend support (#90)
 * Suspend methods use the same intrinsics pattern as [LeaderElectionAspect.aroundLeaderSuspend] and
 * acquire through [SuspendLeaderGroupElectorFactory].
 *
 * ## Mono support (#91)
 * `reactor.core.publisher.Mono` return types use [aroundLeaderMono] and defer group leader
 * acquisition until subscription.
 *
 * ## LeaderElectionInfo (#92)
 * Suspend and Mono branches install [LeaderElectionInfo] with `withContext` while the method body is
 * awaited.
 *
 * ## LockHandleElement / LockStateHolder (T15)
 * - Sync branch: the elector manages the sync lock holder.
 * - Coroutine / reactive branches: the elector already installs `LockHandleElement`; the aspect adds
 *   only [LeaderElectionInfo].
 * - `FAIL_OPEN_RUN` installs a [LeaderLockHandle.FailOpen] sentinel.
 *
 * ## Reentrant pass-through (T15)
 * The sync branch short-circuits when [AopScopeAccess.peekSyncMatching] finds a matching real or
 * fail-open handle.
 */
@Suppress("ReactiveStreamsUnusedPublisher")
@Aspect
class LeaderGroupElectionAspect(
    private val beanSelector: LeaderBeanSelector,
    private val props: LeaderAopProperties,
    private val spel: SpelExpressionEvaluator,
    private val lockNameValidator: LockNameValidator,
    private val recorders: List<LeaderAopMetricsRecorder>,
): SmartInitializingSingleton {

    private val metadataCache = ConcurrentHashMap<Method, GroupAdviceMetadata>()
    private val factoryCache = ConcurrentHashMap<GroupFactoryCacheKey, LeaderGroupElector>()
    private val suspendElectorCache = ConcurrentHashMap<GroupFactoryCacheKey, SuspendLeaderGroupElector>()
    private val hasRecorders = recorders.isNotEmpty()

    @Around("@annotation(io.bluetape4k.leader.annotation.LeaderGroupElection)")
    fun aroundLeader(pjp: ProceedingJoinPoint): Any? {
        val method = (pjp.signature as MethodSignature).method
        val target = pjp.target
        val args = pjp.args

        if (method.returnType.name == FLUX_RETURN_TYPE) {
            return Flux.defer<Any> {
                Flux.error(unsupportedStreamException(method, "Flux"))
            }
        }

        if (method.returnType.name == FLOW_RETURN_TYPE) {
            return flow<Any?> {
                throw unsupportedStreamException(method, "Flow")
            }
        }

        val meta = metadataCache.computeIfAbsent(method) { resolveMetadata(it, target) }

        if (meta.isSuspend) {
            return aroundLeaderSuspend(pjp, meta)
        }

        if (meta.isMono) {
            return aroundLeaderMono(pjp, meta)
        }

        val opts = meta.options
        val coreOpts = opts.toCoreOptions()

        var lockName: String? = null
        var resolvedIdentity: LockIdentity? = null
        val start = System.nanoTime()

        fun resolveIdentity(name: String, branch: AdviceBranch): LockIdentity =
            resolvedIdentity ?: meta.resolveLockIdentity(name, branch).also { resolvedIdentity = it }

        return try {
            val resolvedName = resolveLockName(meta, method, args, target)
            lockName = resolvedName

            // ── Reentrant short-circuit (T15 + Tier 7 P1-1): full LockIdentity 매칭 시에만 short-circuit ──
            val identity = resolveIdentity(resolvedName, AdviceBranch.SYNC)
            val existing = AopScopeAccess.peekSyncMatching(resolvedName)
            when {
                existing is LeaderLockHandle.Real && existing.matchesIdentity(identity)     -> {
                    log.debug { "leader.aop.group.reentrant lockName=$resolvedName depth=${existing.reentryDepth + 1}" }
                    val reentrantHandle = AopScopeAccess.incrementReentryDepth(existing)
                    return AopScopeAccess.withPushedSync(reentrantHandle) {
                        executeBody(pjp, resolvedName, start)
                    }
                }
                existing is LeaderLockHandle.FailOpen && existing.matchesIdentity(identity) -> {
                    log.debug { "leader.aop.group.fail-open.reentrant lockName=$resolvedName" }
                    return AopScopeAccess.withPushedSync(existing) {
                        executeBody(pjp, resolvedName, start)
                    }
                }
            }

            val cacheKey = GroupFactoryCacheKey(meta.factoryBeanName, opts)
            val election = factoryCache.computeIfAbsent(cacheKey) { meta.factory.create(opts) }

            fanOut { it.onLockAttempt(resolvedName, coreOpts) }

            val runResult = election.runIfLeaderResult(resolvedName) {
                // Elector (e.g., AbstractLocalLeaderGroupElector) 내부에서 LockStateHolder.withPushed 호출 — aspect 가 double-push 하지 않음.
                fanOut {
                    it.onLockAcquired(resolvedName, coreOpts, (System.nanoTime() - start).nanoseconds)
                    it.onTaskStarted(resolvedName)
                }
                executeBody(pjp, resolvedName, start)
            }
            when (runResult) {
                is LeaderRunResult.Skipped      -> {
                    if (meta.failureMode == LeaderAspectFailureMode.FAIL_OPEN_RUN) {
                        val failOpenHandle = AopScopeAccess.createFailOpen(identity)
                        fanOut {
                            it.onLockNotAcquired(resolvedName, coreOpts, SkipReason.FAIL_OPEN_FORCED)
                            it.onTaskStarted(resolvedName)
                        }
                        log.debug { "leader.aop.group.fail-open lockName=$resolvedName reason=CONTENTION" }
                        val result = AopScopeAccess.withPushedSync(failOpenHandle) {
                            executeBody(pjp, resolvedName, start)
                        }
                        val elapsed = System.nanoTime() - start
                        fanOut { it.onTaskFinished(resolvedName, elapsed.nanoseconds) }
                        result
                    } else {
                        fanOut { it.onLockNotAcquired(resolvedName, coreOpts, SkipReason.CONTENTION) }
                        log.debug { "leader.aop.group.skipped lockName=$resolvedName reason=CONTENTION" }
                        null
                    }
                }
                is LeaderRunResult.Elected      -> {
                    val elapsed = System.nanoTime() - start
                    fanOut { it.onTaskFinished(resolvedName, elapsed.nanoseconds) }
                    log.debug { "leader.aop.group.elected lockName=$resolvedName elapsedNs=$elapsed" }
                    if (elapsed > meta.leaseTimeWarnThresholdNanos) {
                        log.warn {
                            "leader.aop.group.lease-warn lockName=$resolvedName elapsedNs=$elapsed leaseTimeNs=${opts.leaseTime.inWholeNanoseconds}"
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
            val wrapped =
                LeaderGroupElectionException("leader group backend error for lock '$effectiveName'", backendEx)
            when (meta.failureMode) {
                LeaderAspectFailureMode.INHERIT       -> error("INHERIT must be resolved in resolveMetadata")
                LeaderAspectFailureMode.RETHROW       -> {
                    fanOut { it.onLockNotAcquired(effectiveName, coreOpts, SkipReason.BACKEND_ERROR) }
                    fanOut { it.onTaskFailed(effectiveName, (System.nanoTime() - start).nanoseconds, backendEx) }
                    throw wrapped
                }
                LeaderAspectFailureMode.SKIP          -> {
                    fanOut { it.onLockNotAcquired(effectiveName, coreOpts, SkipReason.BACKEND_ERROR) }
                    fanOut { it.onTaskFailed(effectiveName, (System.nanoTime() - start).nanoseconds, backendEx) }
                    log.warn(backendEx) { "leader.aop.group.skipped lockName=$effectiveName reason=BACKEND_ERROR" }
                    null
                }
                LeaderAspectFailureMode.FAIL_OPEN_RUN -> {
                    val failOpenHandle = AopScopeAccess.createFailOpen(
                        resolveIdentity(effectiveName, AdviceBranch.SYNC)
                    )
                    fanOut { it.onLockNotAcquired(effectiveName, coreOpts, SkipReason.FAIL_OPEN_FORCED) }
                    log.warn(backendEx) { "leader.aop.group.fail-open lockName=$effectiveName reason=BACKEND_ERROR" }
                    fanOut { it.onTaskStarted(effectiveName) }
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
     * Handles suspend methods using the same pattern as [LeaderElectionAspect.aroundLeaderSuspend].
     * Injects [LeaderElectionInfo] via `withContext` during body execution.
     */
    private fun aroundLeaderSuspend(pjp: ProceedingJoinPoint, meta: GroupAdviceMetadata): Any? {
        @Suppress("UNCHECKED_CAST")
        val continuation = pjp.args.last() as Continuation<Any?>
        val start = System.nanoTime()
        val method = (pjp.signature as MethodSignature).method
        val coreOpts = meta.options.toCoreOptions()

        val suspendBlock: suspend () -> Any? = {
            var lockName: String? = null
            var resolvedIdentity: LockIdentity? = null

            fun resolveIdentity(name: String): LockIdentity =
                resolvedIdentity ?: meta.resolveLockIdentity(name, AdviceBranch.COROUTINES)
                    .also { resolvedIdentity = it }

            try {
                val resolvedName = resolveLockName(meta, method, pjp.args, pjp.target)
                lockName = resolvedName
                val cacheKey = GroupFactoryCacheKey(meta.suspendElectorFactoryBeanName, meta.options)
                // Tier 5 C1 — `!!` 제거. suspend create 는 computeIfAbsent 안 호출 불가 — putIfAbsent 패턴 유지.
                val factory = checkNotNull(meta.suspendElectorFactory) {
                    "suspendElectorFactory must be non-null in COROUTINES/REACTIVE branch"
                }
                val elector = suspendElectorCache[cacheKey]
                    ?: factory.create(meta.options).also { suspendElectorCache.putIfAbsent(cacheKey, it) }

                fanOut { it.onLockAttempt(resolvedName, coreOpts) }

                val result = elector.runIfLeader(resolvedName) {
                    fanOut {
                        it.onLockAcquired(resolvedName, coreOpts, (System.nanoTime() - start).nanoseconds)
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
                                log.warn { "leader.aop.group.lease-warn lockName=$resolvedName elapsedNs=$elapsed leaseTimeNs=${meta.options.leaseTime.inWholeNanoseconds}" }
                            }
                            log.debug { "leader.aop.group.elected lockName=$resolvedName elapsedNs=$elapsed" }
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
                            it.onLockNotAcquired(resolvedName, coreOpts, SkipReason.FAIL_OPEN_FORCED)
                            it.onTaskStarted(resolvedName)
                        }
                        log.debug { "leader.aop.group.fail-open lockName=$resolvedName reason=CONTENTION" }
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
                        fanOut { it.onLockNotAcquired(resolvedName, coreOpts, SkipReason.CONTENTION) }
                        log.debug { "leader.aop.group.skipped lockName=$resolvedName reason=CONTENTION" }
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
                val wrapped =
                    LeaderGroupElectionException("leader group backend error for lock '$effectiveName'", backendEx)
                when (meta.failureMode) {
                    LeaderAspectFailureMode.INHERIT       -> error("INHERIT must be resolved in resolveMetadata")
                    LeaderAspectFailureMode.RETHROW       -> {
                        fanOut { it.onLockNotAcquired(effectiveName, coreOpts, SkipReason.BACKEND_ERROR) }
                        fanOut { it.onTaskFailed(effectiveName, (System.nanoTime() - start).nanoseconds, backendEx) }
                        throw wrapped
                    }
                    LeaderAspectFailureMode.SKIP          -> {
                        fanOut { it.onLockNotAcquired(effectiveName, coreOpts, SkipReason.BACKEND_ERROR) }
                        fanOut { it.onTaskFailed(effectiveName, (System.nanoTime() - start).nanoseconds, backendEx) }
                        log.warn(backendEx) { "leader.aop.group.skipped lockName=$effectiveName reason=BACKEND_ERROR" }
                        null
                    }
                    LeaderAspectFailureMode.FAIL_OPEN_RUN -> {
                        val failOpenHandle = AopScopeAccess.createFailOpen(resolveIdentity(effectiveName))
                        fanOut { it.onLockNotAcquired(effectiveName, coreOpts, SkipReason.FAIL_OPEN_FORCED) }
                        log.warn(backendEx) { "leader.aop.group.fail-open lockName=$effectiveName reason=BACKEND_ERROR" }
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
     * Handles methods returning `Mono` using the `Mono.defer { mono { suspendElector.runIfLeader(...) } }` pattern.
     * Injects [LeaderElectionInfo] via `withContext`.
     */
    private fun aroundLeaderMono(pjp: ProceedingJoinPoint, meta: GroupAdviceMetadata): Any? {
        val method = (pjp.signature as MethodSignature).method
        val coreOpts = meta.options.toCoreOptions()

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
                    val cacheKey = GroupFactoryCacheKey(meta.suspendElectorFactoryBeanName, meta.options)
                    val factory = checkNotNull(meta.suspendElectorFactory) {
                        "suspendElectorFactory must be non-null in REACTIVE branch (Mono)"
                    }
                    val elector = suspendElectorCache[cacheKey]
                        ?: factory.create(meta.options).also { suspendElectorCache.putIfAbsent(cacheKey, it) }

                    fanOut { it.onLockAttempt(resolvedName, coreOpts) }

                    val result = elector.runIfLeader(resolvedName) {
                        fanOut {
                            it.onLockAcquired(resolvedName, coreOpts, (System.nanoTime() - start).nanoseconds)
                            it.onTaskStarted(resolvedName)
                        }
                        withContext(LeaderElectionInfo(lockName = resolvedName, wasElected = true)) {
                            try {
                                @Suppress("UNCHECKED_CAST")
                                val bodyResult = (pjp.proceed() as Mono<*>).awaitSingleOrNull()
                                val elapsed = System.nanoTime() - start
                                fanOut { it.onTaskFinished(resolvedName, elapsed.nanoseconds) }
                                if (elapsed > meta.leaseTimeWarnThresholdNanos) {
                                    log.warn { "leader.aop.group.lease-warn lockName=$resolvedName elapsedNs=$elapsed leaseTimeNs=${meta.options.leaseTime.inWholeNanoseconds}" }
                                }
                                log.debug { "leader.aop.group.elected lockName=$resolvedName elapsedNs=$elapsed" }
                                bodyResult
                            } catch (ce: CancellationException) {
                                fanOut { it.onTaskFailed(resolvedName, (System.nanoTime() - start).nanoseconds, ce) }
                                throw ce
                            } catch (bodyEx: Throwable) {
                                fanOut {
                                    it.onTaskFailed(
                                        resolvedName,
                                        (System.nanoTime() - start).nanoseconds,
                                        bodyEx
                                    )
                                }
                                throw BodyThrownMarker(bodyEx)
                            }
                        }
                    }

                    if (result == null) {
                        if (meta.failureMode == LeaderAspectFailureMode.FAIL_OPEN_RUN) {
                            val failOpenHandle = AopScopeAccess.createFailOpen(resolveIdentity(resolvedName))
                            fanOut {
                                it.onLockNotAcquired(resolvedName, coreOpts, SkipReason.FAIL_OPEN_FORCED)
                                it.onTaskStarted(resolvedName)
                            }
                            log.debug { "leader.aop.group.fail-open lockName=$resolvedName reason=CONTENTION" }
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
                            fanOut { it.onLockNotAcquired(resolvedName, coreOpts, SkipReason.CONTENTION) }
                            log.debug { "leader.aop.group.skipped lockName=$resolvedName reason=CONTENTION" }
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
                    val wrapped =
                        LeaderGroupElectionException("leader group backend error for lock '$effectiveName'", backendEx)
                    when (meta.failureMode) {
                        LeaderAspectFailureMode.INHERIT       -> error("INHERIT must be resolved in resolveMetadata")
                        LeaderAspectFailureMode.RETHROW       -> {
                            fanOut { it.onLockNotAcquired(effectiveName, coreOpts, SkipReason.BACKEND_ERROR) }
                            fanOut {
                                it.onTaskFailed(
                                    effectiveName,
                                    (System.nanoTime() - start).nanoseconds,
                                    backendEx
                                )
                            }
                            throw wrapped
                        }
                        LeaderAspectFailureMode.SKIP          -> {
                            fanOut { it.onLockNotAcquired(effectiveName, coreOpts, SkipReason.BACKEND_ERROR) }
                            fanOut {
                                it.onTaskFailed(
                                    effectiveName,
                                    (System.nanoTime() - start).nanoseconds,
                                    backendEx
                                )
                            }
                            log.warn(backendEx) { "leader.aop.group.skipped lockName=$effectiveName reason=BACKEND_ERROR" }
                            null
                        }
                        LeaderAspectFailureMode.FAIL_OPEN_RUN -> {
                            val failOpenHandle = AopScopeAccess.createFailOpen(resolveIdentity(effectiveName))
                            fanOut { it.onLockNotAcquired(effectiveName, coreOpts, SkipReason.FAIL_OPEN_FORCED) }
                            log.warn(backendEx) { "leader.aop.group.fail-open lockName=$effectiveName reason=BACKEND_ERROR" }
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
                                fanOut {
                                    it.onTaskFailed(
                                        effectiveName,
                                        (System.nanoTime() - start).nanoseconds,
                                        bodyEx
                                    )
                                }
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
        val ann = AnnotationLookup.findAnnotationWithTargetFallback<LeaderGroupElection>(method, target)
            ?: error("@LeaderGroupElection not found on ${method.declaringClass.name}#${method.name}")

        ann.maxLeaders.requireGe(2, "ann.maxLeaders")

        val waitTime = DurationParser.parseOrDefault(ann.waitTime, props.defaultWaitTime).toKotlinDuration()
        val leaseTime = DurationParser.parseOrDefault(ann.leaseTime, props.defaultLeaseTime).toKotlinDuration()
        val minLeaseTime = DurationParser.parseNonNegativeOrDefault(
            ann.minLeaseTime,
            java.time.Duration.ZERO,
        ).toKotlinDuration()

        val opts = LeaderGroupElectionOptions(
            maxLeaders = ann.maxLeaders,
            waitTime = waitTime,
            leaseTime = leaseTime,
            minLeaseTime = minLeaseTime,
        )
        val selected = beanSelector.selectGroupElectionFactory(ann.bean, method)
        val literal = if (LITERAL_PATTERN.matches(ann.name)) ann.name else null

        val effectiveFailureMode = if (ann.failureMode == LeaderAspectFailureMode.INHERIT) {
            props.failureMode
        } else {
            ann.failureMode
        }

        val isSuspend = method.parameterTypes.lastOrNull() == Continuation::class.java
        val isMono = !isSuspend && method.returnType.name == "reactor.core.publisher.Mono"
        val (suspendElectorFactory, suspendElectorFactoryBeanName) = if (isSuspend || isMono) {
            val suspendSelected = beanSelector.selectSuspendGroupElectorFactory(ann.bean, method)
            suspendSelected.bean to suspendSelected.beanName
        } else {
            null to ""
        }

        return GroupAdviceMetadata(
            nameExpression = ann.name,
            literalName = literal,
            options = opts,
            factoryBeanName = selected.beanName,
            factory = selected.bean,
            failureMode = effectiveFailureMode,
            leaseTimeWarnThresholdNanos = (leaseTime.inWholeNanoseconds * LEASE_WARN_RATIO).toLong(),
            isSuspend = isSuspend,
            isMono = isMono,
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

    private fun LeaderGroupElectionOptions.toCoreOptions(): io.bluetape4k.leader.LeaderElectionOptions =
        io.bluetape4k.leader.LeaderElectionOptions(
            waitTime = waitTime,
            leaseTime = leaseTime,
            minLeaseTime = minLeaseTime,
        )

    private fun unsupportedStreamException(method: Method, returnShape: String): LeaderGroupElectionException =
        LeaderGroupElectionException(
            "@LeaderGroupElection does not support $returnShape streams yet: " +
                    "${method.declaringClass.name}#${method.name}",
        )

    private data class GroupAdviceMetadata(
        val nameExpression: String,
        val literalName: String?,
        val options: LeaderGroupElectionOptions,
        val factoryBeanName: String,
        val factory: LeaderGroupElectorFactory,
        val failureMode: LeaderAspectFailureMode,
        val leaseTimeWarnThresholdNanos: Long,
        val isSuspend: Boolean,
        val isMono: Boolean,
        val suspendElectorFactory: SuspendLeaderGroupElectorFactory?,
        val suspendElectorFactoryBeanName: String,
    ) {

        /**
         * Creates a [LockIdentity] for the given [branch].
         *
         * Because this is a group annotation, `kind = GROUP` and `groupParams = GroupParams(maxLeaders)`.
         * `factoryBeanName` is excluded from `equals/hashCode`, so nested sync ↔ suspend calls
         * are recognised as the same lock (Step 3-P R3 mitigation).
         */
        fun resolveLockIdentity(lockName: String, branch: AdviceBranch): LockIdentity {
            val beanName = when (branch) {
                AdviceBranch.SYNC                              -> factoryBeanName
                AdviceBranch.COROUTINES, AdviceBranch.REACTIVE -> suspendElectorFactoryBeanName
            }
            return LockIdentity(
                lockName = lockName,
                kind = LockIdentity.AnnotationKind.GROUP,
                factoryBeanName = beanName,
                groupParams = LockIdentity.GroupParams(maxLeaders = options.maxLeaders),
            )
        }
    }

    companion object: KLogging() {
        private val LITERAL_PATTERN = Regex("^[A-Za-z0-9_:.\\-]+$")
        private const val LEASE_WARN_RATIO = 0.8
        private const val FLUX_RETURN_TYPE = "reactor.core.publisher.Flux"
        private const val FLOW_RETURN_TYPE = "kotlinx.coroutines.flow.Flow"
    }
}
