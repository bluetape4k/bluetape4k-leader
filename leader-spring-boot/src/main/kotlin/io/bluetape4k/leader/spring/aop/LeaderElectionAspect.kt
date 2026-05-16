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
 * Aspect that applies `@LeaderElection` to sync `T?`, suspend `T?`, `Mono<T>`,
 * `Flux<T>`, and Kotlin `Flow<T>` methods.
 *
 * ## CTW (Freefair post-compile weaving)
 * The auto-configuration registers this aspect as a bean without requiring
 * `@EnableAspectJAutoProxy`.
 *
 * ## Suspend support (#90)
 * Methods whose last parameter is [Continuation] use [aroundLeaderSuspend].
 * The implementation follows the `startCoroutineUninterceptedOrReturn` and
 * `suspendCoroutineUninterceptedOrReturn` intrinsics pattern.
 *
 * ## Mono support (#91)
 * `reactor.core.publisher.Mono` return types use [aroundLeaderMono] and defer
 * leader acquisition until subscription.
 *
 * ## Stream support (#74)
 * `Flux<T>` and `Flow<T>` return types hold the leader lock for the stream
 * lifecycle. They require `autoExtend=true` or `streamBounded=true`, and unsafe
 * stream configurations are rejected again at subscription or collection time
 * even when startup validation is disabled.
 *
 * ## LeaderElectionInfo (#92)
 * Suspend, Mono, Flux, and Flow branches install [LeaderElectionInfo] with
 * `withContext` while the method body is collected or awaited.
 *
 * ## LockHandleElement / LockStateHolder (T14)
 * - Sync branch: the elector manages `LockStateHolder`, so [io.bluetape4k.leader.LockAssert]
 *   and [io.bluetape4k.leader.LockExtender] see the active lock.
 * - Coroutine / reactive branches: the elector already installs `LockHandleElement`; the aspect
 *   adds only [LeaderElectionInfo].
 * - `FAIL_OPEN_RUN` installs a [LeaderLockHandle.FailOpen] sentinel so the body can detect the
 *   fail-open scope.
 *
 * ## Reentrant pass-through (T14)
 * The sync branch short-circuits when [AopScopeAccess.peekSyncMatching] finds a matching real
 * handle. Coroutine branches delegate reentrant handling to the elector because the lock handle
 * lives in the coroutine context.
 */
@Aspect
class LeaderElectionAspect(
    private val beanSelector: LeaderBeanSelector,
    private val props: LeaderAopProperties,
    private val spel: SpelExpressionEvaluator,
    private val lockNameValidator: LockNameValidator,
    private val recorders: List<LeaderAopMetricsRecorder>,
) : SmartInitializingSingleton {

    private val metadataCache = ConcurrentHashMap<Method, AdviceMetadata>()
    private val factoryCache = ConcurrentHashMap<FactoryCacheKey, LeaderElector>()
    private val suspendElectorCache = ConcurrentHashMap<FactoryCacheKey, SuspendLeaderElector>()
    private val hasRecorders = recorders.isNotEmpty()

    @Around("@annotation(io.bluetape4k.leader.annotation.LeaderElection)")
    fun aroundLeader(pjp: ProceedingJoinPoint): Any? {
        val method = (pjp.signature as MethodSignature).method
        val target = pjp.target
        val args = pjp.args

        if (method.returnType.name == FLUX_RETURN_TYPE) {
            return Flux.defer<Any> {
                val meta = metadataCache.computeIfAbsent(method) { resolveMetadata(it, target) }
                @Suppress("UNCHECKED_CAST")
                aroundLeaderFlux(pjp, meta) as Flux<Any>
            }
        }

        if (method.returnType.name == FLOW_RETURN_TYPE) {
            return aroundLeaderFlow(pjp, method, target)
        }

        if (method.returnType.name == MONO_RETURN_TYPE) {
            return Mono.defer<Any> {
                val meta = metadataCache.computeIfAbsent(method) { resolveMetadata(it, target) }
                @Suppress("UNCHECKED_CAST")
                aroundLeaderMono(pjp, meta) as Mono<Any>
            }
        }

        val meta = metadataCache.computeIfAbsent(method) { resolveMetadata(it, target) }

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
     * suspend 메서드 처리 — `startCoroutineUninterceptedOrReturn` intrinsics 패턴.
     * 본문 실행 시 [LeaderElectionInfo] 를 `withContext` 로 CoroutineContext 에 주입.
     * suspend elector 가 이미 `withContext(LockHandleElement(handle))` 를 push 하므로
     * aspect 는 [LeaderElectionInfo] 만 추가 — handle context 는 elector 에서 제공.
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
     * Handles methods returning `Flux`.
     *
     * The returned [Flux] is cold. Each subscription resolves the lock name,
     * acquires the leader lock, collects the user [Flux], and releases the lock
     * only after complete/error/cancel.
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
     * Handles methods returning Kotlin `Flow`.
     *
     * Uses [channelFlow] instead of `flow { withContext { emit(...) } }` so the
     * leader context can be installed while values are sent without violating
     * Kotlin Flow context-preservation rules.
     */
    private fun aroundLeaderFlow(
        pjp: ProceedingJoinPoint,
        method: Method,
        target: Any,
    ): Flow<Any?> =
        channelFlow {
            val meta = metadataCache.computeIfAbsent(method) { resolveMetadata(it, target) }
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
     * `Mono` 반환 타입 메서드 처리 — `Mono.defer { mono { suspendElector.runIfLeader(...) } }` 패턴.
     *
     * `Mono.defer` 로 subscribe 당 락 1회 보장.
     * [LeaderElectionInfo] 를 `withContext` 로 주입하여 CoroutineContext 전파.
     * suspend elector 가 `withContext(LockHandleElement(handle))` 를 이미 push.
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
        val ann = AnnotationLookup.findAnnotationWithTargetFallback<LeaderElection>(method, target)
            ?: error("@LeaderElection not found on ${method.declaringClass.name}#${method.name}")

        val waitTime = DurationParser.parseOrDefault(ann.waitTime, props.defaultWaitTime).toKotlinDuration()
        val leaseTime = DurationParser.parseOrDefault(ann.leaseTime, props.defaultLeaseTime).toKotlinDuration()
        val minLeaseTime = DurationParser.parseNonNegativeOrDefault(
            ann.minLeaseTime,
            java.time.Duration.ZERO,
        ).toKotlinDuration()

        val opts = LeaderElectionOptions(
            waitTime = waitTime,
            leaseTime = leaseTime,
            minLeaseTime = minLeaseTime,
            autoExtend = ann.autoExtend,
        )
        val selected = beanSelector.selectElectionFactory(ann.bean, method)
        val literal = if (LITERAL_PATTERN.matches(ann.name)) ann.name else null

        val effectiveFailureMode = if (ann.failureMode == LeaderAspectFailureMode.INHERIT) {
            props.failureMode
        } else {
            ann.failureMode
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
            branch = branch,
            isSuspend = isSuspend,
            isMono = isMono,
            isFlux = isFlux,
            isFlow = isFlow,
            streamBounded = ann.streamBounded,
            suspendElectorFactory = suspendElectorFactory,
            suspendElectorFactoryBeanName = suspendElectorFactoryBeanName,
            annotationKind = LockIdentity.AnnotationKind.SINGLE,
            groupParams = null,
        )
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
