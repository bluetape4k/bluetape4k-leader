package io.bluetape4k.leader.spring.aop

import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectionException
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import io.bluetape4k.leader.annotation.LeaderGroupElection
import io.bluetape4k.leader.coroutines.LeaderElectionInfo
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
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
 * `@LeaderGroupElection` 어노테이션 처리 Aspect — sync `T?`, suspend `T?`, `Mono<T>` 지원.
 *
 * [LeaderElectionAspect] 와 동일 패턴 + `maxLeaders` 분기. backend 예외는
 * [LeaderGroupElectionException] 으로 wrapping.
 *
 * ## suspend 지원 (#90)
 * [LeaderElectionAspect.aroundLeaderSuspend] 와 동일 패턴 — [SuspendLeaderGroupElectorFactory] 사용.
 *
 * ## Mono 지원 (#91)
 * 반환 타입이 `reactor.core.publisher.Mono` 인 메서드 → [aroundLeaderMono] 분기.
 *
 * ## LeaderElectionInfo (#92)
 * suspend / Mono 분기 본문 실행 시 [LeaderElectionInfo] 를 `withContext` 로 주입.
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
    private val suspendElectorCache = ConcurrentHashMap<GroupFactoryCacheKey, SuspendLeaderGroupElector>()
    private val hasRecorders = recorders.isNotEmpty()

    @Around("@annotation(io.bluetape4k.leader.annotation.LeaderGroupElection)")
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
        val coreOpts = opts.toCoreOptions()

        var lockName: String? = null
        val start = System.nanoTime()

        return try {
            val resolvedName = resolveLockName(meta, method, args, target)
            lockName = resolvedName

            val cacheKey = GroupFactoryCacheKey(meta.factoryBeanName, opts)
            val election = factoryCache.computeIfAbsent(cacheKey) { meta.factory.create(opts) }

            fanOut { it.onLockAttempt(resolvedName, coreOpts) }

            val runResult = election.runIfLeaderResult(resolvedName) {
                fanOut {
                    it.onLockAcquired(resolvedName, coreOpts, (System.nanoTime() - start).nanoseconds)
                    it.onTaskStarted(resolvedName)
                }
                executeBody(pjp, resolvedName, start)
            }
            when (runResult) {
                is LeaderRunResult.Skipped -> {
                    if (meta.failureMode == LeaderAspectFailureMode.FAIL_OPEN_RUN) {
                        fanOut {
                            it.onLockNotAcquired(resolvedName, coreOpts, SkipReason.FAIL_OPEN_FORCED)
                            it.onTaskStarted(resolvedName)
                        }
                        log.debug { "leader.aop.fail-open lockName=$resolvedName reason=CONTENTION" }
                        val result = executeBody(pjp, resolvedName, start)
                        val elapsed = System.nanoTime() - start
                        fanOut { it.onTaskFinished(resolvedName, elapsed.nanoseconds) }
                        result
                    } else {
                        fanOut { it.onLockNotAcquired(resolvedName, coreOpts, SkipReason.CONTENTION) }
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
            val wrapped = LeaderGroupElectionException("leader group backend error for lock '$effectiveName'", backendEx)
            when (meta.failureMode) {
                LeaderAspectFailureMode.INHERIT -> error("INHERIT must be resolved in resolveMetadata")
                LeaderAspectFailureMode.RETHROW -> {
                    fanOut { it.onLockNotAcquired(effectiveName, coreOpts, SkipReason.BACKEND_ERROR) }
                    fanOut { it.onTaskFailed(effectiveName, (System.nanoTime() - start).nanoseconds, backendEx) }
                    throw wrapped
                }
                LeaderAspectFailureMode.SKIP -> {
                    fanOut { it.onLockNotAcquired(effectiveName, coreOpts, SkipReason.BACKEND_ERROR) }
                    fanOut { it.onTaskFailed(effectiveName, (System.nanoTime() - start).nanoseconds, backendEx) }
                    log.warn(backendEx) { "leader.aop.skipped lockName=$effectiveName reason=BACKEND_ERROR" }
                    null
                }
                LeaderAspectFailureMode.FAIL_OPEN_RUN -> {
                    fanOut { it.onLockNotAcquired(effectiveName, coreOpts, SkipReason.FAIL_OPEN_FORCED) }
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
     * suspend 메서드 처리 — [LeaderElectionAspect.aroundLeaderSuspend] 와 동일 패턴.
     * 본문 실행 시 [LeaderElectionInfo] 를 `withContext` 로 주입.
     */
    private fun aroundLeaderSuspend(pjp: ProceedingJoinPoint, meta: GroupAdviceMetadata): Any? {
        @Suppress("UNCHECKED_CAST")
        val continuation = pjp.args.last() as Continuation<Any?>
        val start = System.nanoTime()
        val method = (pjp.signature as MethodSignature).method
        val coreOpts = meta.options.toCoreOptions()

        val suspendBlock: suspend () -> Any? = {
            var lockName: String? = null
            try {
                val resolvedName = resolveLockName(meta, method, pjp.args, pjp.target)
                lockName = resolvedName
                val cacheKey = GroupFactoryCacheKey(meta.suspendElectorFactoryBeanName, meta.options)
                val elector = suspendElectorCache[cacheKey]
                    ?: meta.suspendElectorFactory!!.create(meta.options)
                        .also { suspendElectorCache.putIfAbsent(cacheKey, it) }

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
                        fanOut {
                            it.onLockNotAcquired(resolvedName, coreOpts, SkipReason.FAIL_OPEN_FORCED)
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
                        fanOut { it.onLockNotAcquired(resolvedName, coreOpts, SkipReason.CONTENTION) }
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
                val wrapped = LeaderGroupElectionException("leader group backend error for lock '$effectiveName'", backendEx)
                when (meta.failureMode) {
                    LeaderAspectFailureMode.INHERIT -> error("INHERIT must be resolved in resolveMetadata")
                    LeaderAspectFailureMode.RETHROW -> {
                        fanOut { it.onLockNotAcquired(effectiveName, coreOpts, SkipReason.BACKEND_ERROR) }
                        fanOut { it.onTaskFailed(effectiveName, (System.nanoTime() - start).nanoseconds, backendEx) }
                        throw wrapped
                    }
                    LeaderAspectFailureMode.SKIP -> {
                        fanOut { it.onLockNotAcquired(effectiveName, coreOpts, SkipReason.BACKEND_ERROR) }
                        fanOut { it.onTaskFailed(effectiveName, (System.nanoTime() - start).nanoseconds, backendEx) }
                        log.warn(backendEx) { "leader.aop.skipped lockName=$effectiveName reason=BACKEND_ERROR" }
                        null
                    }
                    LeaderAspectFailureMode.FAIL_OPEN_RUN -> {
                        fanOut { it.onLockNotAcquired(effectiveName, coreOpts, SkipReason.FAIL_OPEN_FORCED) }
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
     * `Mono` 반환 타입 메서드 처리 — `Mono.defer { mono { suspendElector.runIfLeader(...) } }` 패턴.
     * [LeaderElectionInfo] 를 `withContext` 로 주입.
     */
    private fun aroundLeaderMono(pjp: ProceedingJoinPoint, meta: GroupAdviceMetadata): Any? {
        val method = (pjp.signature as MethodSignature).method
        val coreOpts = meta.options.toCoreOptions()

        return Mono.defer {
            val start = System.nanoTime()
            mono {
                var lockName: String? = null
                try {
                    val resolvedName = resolveLockName(meta, method, pjp.args, pjp.target)
                    lockName = resolvedName
                    val cacheKey = GroupFactoryCacheKey(meta.suspendElectorFactoryBeanName, meta.options)
                    val elector = suspendElectorCache[cacheKey]
                        ?: meta.suspendElectorFactory!!.create(meta.options)
                            .also { suspendElectorCache.putIfAbsent(cacheKey, it) }

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
                            fanOut {
                                it.onLockNotAcquired(resolvedName, coreOpts, SkipReason.FAIL_OPEN_FORCED)
                                it.onTaskStarted(resolvedName)
                            }
                            log.debug { "leader.aop.fail-open lockName=$resolvedName reason=CONTENTION" }
                            @Suppress("UNCHECKED_CAST")
                            val failOpenResult = (pjp.proceed() as Mono<*>).awaitSingleOrNull()
                            fanOut { it.onTaskFinished(resolvedName, (System.nanoTime() - start).nanoseconds) }
                            failOpenResult
                        } else {
                            fanOut { it.onLockNotAcquired(resolvedName, coreOpts, SkipReason.CONTENTION) }
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
                    val wrapped = LeaderGroupElectionException("leader group backend error for lock '$effectiveName'", backendEx)
                    when (meta.failureMode) {
                        LeaderAspectFailureMode.INHERIT -> error("INHERIT must be resolved in resolveMetadata")
                        LeaderAspectFailureMode.RETHROW -> {
                            fanOut { it.onLockNotAcquired(effectiveName, coreOpts, SkipReason.BACKEND_ERROR) }
                            fanOut { it.onTaskFailed(effectiveName, (System.nanoTime() - start).nanoseconds, backendEx) }
                            throw wrapped
                        }
                        LeaderAspectFailureMode.SKIP -> {
                            fanOut { it.onLockNotAcquired(effectiveName, coreOpts, SkipReason.BACKEND_ERROR) }
                            fanOut { it.onTaskFailed(effectiveName, (System.nanoTime() - start).nanoseconds, backendEx) }
                            log.warn(backendEx) { "leader.aop.skipped lockName=$effectiveName reason=BACKEND_ERROR" }
                            null
                        }
                        LeaderAspectFailureMode.FAIL_OPEN_RUN -> {
                            fanOut { it.onLockNotAcquired(effectiveName, coreOpts, SkipReason.FAIL_OPEN_FORCED) }
                            log.warn(backendEx) { "leader.aop.fail-open lockName=$effectiveName reason=BACKEND_ERROR" }
                            fanOut { it.onTaskStarted(effectiveName) }
                            try {
                                @Suppress("UNCHECKED_CAST")
                                val failOpenResult = (pjp.proceed() as Mono<*>).awaitSingleOrNull()
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

    private class BodyThrownMarker(override val cause: Throwable) : RuntimeException(cause)

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
        val ann = AnnotationLookup.findAnnotationWithTargetFallback(method, target, LeaderGroupElection::class.java)
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
    )

    companion object: KLogging() {
        private val LITERAL_PATTERN = Regex("^[A-Za-z0-9_:.\\-]+$")
        private const val LEASE_WARN_RATIO = 0.8
    }
}
