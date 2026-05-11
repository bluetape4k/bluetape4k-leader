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
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.withContext
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.beans.factory.SmartInitializingSingleton
import reactor.core.publisher.Mono
import java.lang.reflect.Method
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.toKotlinDuration

/**
 * `@LeaderElection` 어노테이션 처리 Aspect — sync `T?`, suspend `T?`, `Mono<T>` 지원.
 *
 * ## CTW (Freefair post-compile weaving)
 * `@EnableAspectJAutoProxy` 미사용. autoconfig 가 `@Bean` 으로 등록.
 *
 * ## suspend 지원 (#90)
 * 마지막 파라미터가 [Continuation] 인 메서드 → [aroundLeaderSuspend] 분기.
 * `startCoroutineUninterceptedOrReturn` + `suspendCoroutineUninterceptedOrReturn` intrinsics 패턴.
 *
 * ## Mono 지원 (#91)
 * 반환 타입이 `reactor.core.publisher.Mono` 인 메서드 → [aroundLeaderMono] 분기.
 * `Mono.defer { mono { suspendElector.runIfLeader(...) } }` 패턴 — subscribe 당 락 1회.
 *
 * ## LeaderElectionInfo (#92)
 * suspend / Mono 분기 본문 실행 시 [LeaderElectionInfo] 를 `withContext` 로 주입.
 * 사용자는 `coroutineContext[LeaderElectionInfo]` 로 선출 정보 접근 가능.
 *
 * ## LockHandleElement / LockStateHolder (T14)
 * - sync 분기: [AopScopeAccess.withPushedSync] 로 `LockStateHolder` 에 handle push — [io.bluetape4k.leader.LockAssert] / [io.bluetape4k.leader.LockExtender] 동작.
 * - suspend / Mono 분기: elector 가 `withContext(LockHandleElement(...))` 로 이미 push — aspect 는 [LeaderElectionInfo] 만 추가.
 * - FAIL_OPEN_RUN 분기: [LeaderLockHandle.FailOpen] sentinel 을 push 하여 body 가 fail-open scope 인식.
 *
 * ## Reentrant pass-through (T14)
 * sync 분기 진입 전 [AopScopeAccess.peekSyncMatching] 으로 동일 lockName 보유 여부 확인.
 * 이미 보유 중이면 backend re-acquire 없이 body 직접 실행 (backend acquire counter = 1).
 * suspend 분기는 elector 에서 `LockHandleElement` 가 coroutine context 에 있으므로
 * 재진입 시 elector 가 직접 pass-through (Mutex 비재진입이므로 aspect-level 단락 불필요).
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

        val meta = metadataCache.computeIfAbsent(method) { resolveMetadata(it, target) }

        if (meta.isSuspend) {
            return aroundLeaderSuspend(pjp, meta)
        }

        if (meta.isMono) {
            return aroundLeaderMono(pjp, meta)
        }

        val opts = meta.options
        var lockName: String? = null
        val start = System.nanoTime()

        return try {
            val resolvedName = resolveLockName(meta, method, args, target)
            lockName = resolvedName

            // ── Reentrant short-circuit (T14): sync 분기에서 동일 lockName 이미 보유 중이면 backend 미호출 ──
            val existing = AopScopeAccess.peekSyncMatching(resolvedName)
            if (existing is LeaderLockHandle.Real) {
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
                        val identity = meta.resolveLockIdentity(resolvedName, AdviceBranch.SYNC)
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
                    val identity = meta.resolveLockIdentity(effectiveName, AdviceBranch.SYNC)
                    val failOpenHandle = AopScopeAccess.createFailOpen(identity)
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
                    withContext(LeaderElectionInfo(lockName = resolvedName, wasElected = true)) {
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
                }

                if (result == null) {
                    if (meta.failureMode == LeaderAspectFailureMode.FAIL_OPEN_RUN) {
                        val identity = meta.resolveLockIdentity(resolvedName, AdviceBranch.COROUTINES)
                        val failOpenHandle = AopScopeAccess.createFailOpen(identity)
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
                                    newArgs[newArgs.lastIndex] = innerCont as Continuation<Any?>
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
                        val identity = meta.resolveLockIdentity(effectiveName, AdviceBranch.COROUTINES)
                        val failOpenHandle = AopScopeAccess.createFailOpen(identity)
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
                                    newArgs[newArgs.lastIndex] = innerCont as Continuation<Any?>
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
                            val identity = meta.resolveLockIdentity(resolvedName, AdviceBranch.REACTIVE)
                            val failOpenHandle = AopScopeAccess.createFailOpen(identity)
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
                            val identity = meta.resolveLockIdentity(effectiveName, AdviceBranch.REACTIVE)
                            val failOpenHandle = AopScopeAccess.createFailOpen(identity)
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

        val isSuspend = method.parameterTypes.lastOrNull() == Continuation::class.java
        val isMono = !isSuspend && method.returnType.name == "reactor.core.publisher.Mono"
        val branch = when {
            isSuspend -> AdviceBranch.COROUTINES
            isMono -> AdviceBranch.REACTIVE
            else -> AdviceBranch.SYNC
        }

        val (suspendElectorFactory, suspendElectorFactoryBeanName) = if (isSuspend || isMono) {
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
            suspendElectorFactory = suspendElectorFactory,
            suspendElectorFactoryBeanName = suspendElectorFactoryBeanName,
            annotationKind = LockIdentity.AnnotationKind.SINGLE,
            groupParams = null,
        )
    }

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
    }
}
