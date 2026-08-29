package io.bluetape4k.leader.benchmark

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderLeaseExtensionObservationScope
import io.bluetape4k.leader.LeaderLeaseExtensionObservers
import io.bluetape4k.leader.LockExtender
import io.bluetape4k.leader.annotation.LeaderElection
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.bluetape4k.leader.local.LocalLeaderElector
import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.spring.aop.LeaderBeanSelector
import io.bluetape4k.leader.spring.aop.LeaderElectionAspect
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.leader.spring.aop.util.LockNameValidator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.reactor.flux
import kotlinx.coroutines.reactor.mono
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.Signature
import org.aspectj.lang.reflect.MethodSignature
import org.aspectj.runtime.internal.AroundClosure
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import org.springframework.beans.factory.support.StaticListableBeanFactory
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@State(Scope.Benchmark)
class SpringLeaderAdviceBenchmark {

    @Param("none", "noop")
    lateinit var instrumentation: String

    private lateinit var directElector: LeaderElector
    private lateinit var directSuspendElector: SuspendLeaderElector
    private lateinit var syncStaticAspect: LeaderElectionAspect
    private lateinit var syncSpelAspect: LeaderElectionAspect
    private lateinit var suspendStaticAspect: LeaderElectionAspect
    private lateinit var suspendSpelAspect: LeaderElectionAspect
    private lateinit var syncStaticPjp: ProceedingJoinPoint
    private lateinit var syncSpelPjp: ProceedingJoinPoint
    private lateinit var suspendStaticPjp: ProceedingJoinPoint
    private lateinit var suspendSpelPjp: ProceedingJoinPoint

    @Setup
    fun setup() {
        val options = LeaderElectionOptions(waitTime = 0.milliseconds, leaseTime = 30.seconds)
        val syncFactory = LeaderElectorFactory { LocalLeaderElector(it) }
        val suspendFactory = SuspendLeaderElectorFactory { LocalSuspendLeaderElector(it) }
        val beanSelector = leaderBeanSelector(syncFactory, suspendFactory)
        val recorders = when (instrumentation) {
            "none" -> emptyList()
            "noop" -> listOf(LeaderAopMetricsRecorder.NoOp)
            else -> error("Unsupported instrumentation: $instrumentation")
        }

        directElector = syncFactory.create(options)
        directSuspendElector = runBlocking { suspendFactory.create(options) }
        syncStaticAspect = newAspect(beanSelector, recorders)
        syncSpelAspect = newAspect(beanSelector, recorders)
        suspendStaticAspect = newAspect(beanSelector, recorders)
        suspendSpelAspect = newAspect(beanSelector, recorders)

        val syncTarget = SyncAdviceServiceImpl()
        syncStaticPjp = syncJoinPoint(SyncAdviceService::class.java.getDeclaredMethod("runStatic"), syncTarget, emptyArray())
        syncSpelPjp = syncJoinPoint(
            SyncAdviceService::class.java.getDeclaredMethod("runSpel", String::class.java),
            syncTarget,
            arrayOf("ap-northeast-2"),
        )

        val suspendTarget = SuspendAdviceServiceImpl()
        suspendStaticPjp = suspendJoinPoint("runStatic", suspendTarget, emptyArray())
        suspendSpelPjp = suspendJoinPoint("runSpel", suspendTarget, arrayOf("ap-northeast-2"))
    }

    @Benchmark
    fun directSync(blackhole: Blackhole) {
        blackhole.consume(directElector.runIfLeader("spring-advice-direct-sync") { RESULT })
    }

    @Benchmark
    fun adviceSyncStaticName(blackhole: Blackhole) {
        blackhole.consume(syncStaticAspect.aroundLeader(syncStaticPjp))
    }

    @Benchmark
    fun adviceSyncSpelName(blackhole: Blackhole) {
        blackhole.consume(syncSpelAspect.aroundLeader(syncSpelPjp))
    }

    @Benchmark
    fun directSuspend(blackhole: Blackhole) = runBlocking {
        blackhole.consume(directSuspendElector.runIfLeader("spring-advice-direct-suspend") { RESULT })
    }

    @Benchmark
    fun adviceSuspendStaticName(blackhole: Blackhole) = runBlocking {
        blackhole.consume(runSuspendAspect(suspendStaticAspect, suspendStaticPjp))
    }

    @Benchmark
    fun adviceSuspendSpelName(blackhole: Blackhole) = runBlocking {
        blackhole.consume(runSuspendAspect(suspendSpelAspect, suspendSpelPjp))
    }

    private fun leaderBeanSelector(
        syncFactory: LeaderElectorFactory,
        suspendFactory: SuspendLeaderElectorFactory,
    ): LeaderBeanSelector {
        val beanFactory = StaticListableBeanFactory()
        beanFactory.addBean("localLeaderElectorFactory", syncFactory)
        beanFactory.addBean("localSuspendLeaderElectorFactory", suspendFactory)
        return LeaderBeanSelector(beanFactory)
    }

    private fun newAspect(
        beanSelector: LeaderBeanSelector,
        recorders: List<LeaderAopMetricsRecorder>,
    ): LeaderElectionAspect =
        LeaderElectionAspect(
            beanSelector = beanSelector,
            props = LeaderAopProperties(),
            spel = SpelExpressionEvaluator(embeddedValueResolver = { it }, allowMethodInvocation = false),
            lockNameValidator = LockNameValidator(prefix = ""),
            recorders = recorders,
        )

    private fun syncJoinPoint(
        method: Method,
        target: Any,
        args: Array<Any?>,
    ): ProceedingJoinPoint =
        joinPoint(method = method, target = target, argsProvider = { args }, proceed = { RESULT })

    private fun suspendJoinPoint(
        methodName: String,
        target: Any,
        args: Array<Any?>,
    ): ProceedingJoinPoint {
        val method = SuspendAdviceService::class.java.getDeclaredMethod(
            methodName,
            *args.map { it!!::class.java }.toTypedArray(),
            Continuation::class.java,
        )
        return joinPoint(
            method = method,
            target = target,
            argsProvider = { continuation -> args + continuation },
            proceed = { RESULT },
        )
    }

    private suspend fun runSuspendAspect(aspect: LeaderElectionAspect, pjp: ProceedingJoinPoint): Any? =
        suspendCancellableCoroutine { continuation ->
            val result = aspect.aroundLeader(withContinuation(pjp, continuation))
            @Suppress("SuspiciousEqualsCombination")
            if (result !== COROUTINE_SUSPENDED) {
                continuation.resume(result)
            }
        }

    private fun withContinuation(
        pjp: ProceedingJoinPoint,
        continuation: Continuation<Any?>,
    ): ProceedingJoinPoint =
        (pjp as BenchmarkProceedingJoinPoint).withContinuation(continuation)

    private fun joinPoint(
        method: Method,
        target: Any,
        argsProvider: (Continuation<Any?>) -> Array<Any?>,
        proceed: () -> Any?,
    ): ProceedingJoinPoint =
        BenchmarkProceedingJoinPoint(method, target, argsProvider, proceed)

    private interface SyncAdviceService {
        fun runStatic(): String?
        fun runSpel(region: String): String?
    }

    private class SyncAdviceServiceImpl : SyncAdviceService {
        @LeaderElection(name = "spring-advice-static")
        override fun runStatic(): String? = RESULT

        @LeaderElection(name = "'spring-advice-' + #p0")
        override fun runSpel(region: String): String? = "$RESULT-$region"
    }

    private interface SuspendAdviceService {
        suspend fun runStatic(): String?
        suspend fun runSpel(region: String): String?
    }

    private class SuspendAdviceServiceImpl : SuspendAdviceService {
        @LeaderElection(name = "spring-advice-suspend-static")
        override suspend fun runStatic(): String? = RESULT

        @LeaderElection(name = "'spring-advice-suspend-' + #p0")
        override suspend fun runSpel(region: String): String? = "$RESULT-$region"
    }

    private class BenchmarkProceedingJoinPoint(
        private val method: Method,
        private val target: Any,
        private val argsProvider: (Continuation<Any?>) -> Array<Any?>,
        private val proceed: () -> Any?,
        private val continuation: Continuation<Any?>? = null,
    ) : ProceedingJoinPoint {

        private val signature = methodSignature(method)

        fun withContinuation(continuation: Continuation<Any?>): BenchmarkProceedingJoinPoint =
            BenchmarkProceedingJoinPoint(method, target, argsProvider, proceed, continuation)

        override fun `set$AroundClosure`(arc: AroundClosure?) = Unit

        override fun proceed(): Any? = proceed.invoke()

        override fun proceed(args: Array<Any?>?): Any? = proceed.invoke()

        override fun getThis(): Any = target

        override fun getTarget(): Any = target

        override fun getArgs(): Array<Any?> =
            argsProvider(continuation ?: NoopContinuation)

        override fun getSignature(): Signature = signature

        override fun getSourceLocation() = null

        override fun getKind(): String = "method-execution"

        override fun getStaticPart(): org.aspectj.lang.JoinPoint.StaticPart = staticPart(signature)

        override fun toShortString(): String = method.name

        override fun toLongString(): String = method.toGenericString()
    }

    companion object {
        private const val RESULT = "spring-advice-ok"

        private object NoopContinuation : Continuation<Any?> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<Any?>) = Unit
        }

        private fun methodSignature(method: Method): MethodSignature =
            proxy(MethodSignature::class.java) { invoked, _ ->
                when (invoked.name) {
                    "getMethod" -> method
                    "getReturnType" -> method.returnType
                    "getParameterTypes" -> method.parameterTypes
                    "getParameterNames" -> method.parameters.map { it.name }.toTypedArray()
                    "getExceptionTypes" -> method.exceptionTypes
                    "getName" -> method.name
                    "getModifiers" -> method.modifiers
                    "getDeclaringType" -> method.declaringClass
                    "getDeclaringTypeName" -> method.declaringClass.name
                    "toShortString" -> method.name
                    "toLongString" -> method.toGenericString()
                    "toString" -> method.toString()
                    "hashCode" -> method.hashCode()
                    "equals" -> false
                    else -> null
                }
            }

        private fun staticPart(signature: Signature): org.aspectj.lang.JoinPoint.StaticPart =
            proxy(org.aspectj.lang.JoinPoint.StaticPart::class.java) { invoked, _ ->
                when (invoked.name) {
                    "getSignature" -> signature
                    "getKind" -> "method-execution"
                    "getId" -> 0
                    "toShortString" -> signature.toShortString()
                    "toLongString" -> signature.toLongString()
                    "toString" -> signature.toString()
                    "hashCode" -> signature.hashCode()
                    "equals" -> false
                    else -> null
                }
            }

        private fun <T> proxy(type: Class<T>, handler: (Method, Array<Any?>?) -> Any?): T =
            type.cast(
                Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                    handler(method, args)
                }
            )
    }
}

/** Issue #741의 no-observer fast path와 scoped dispatch 비용을 같은 process-local API에서 비교합니다. */
@State(Scope.Benchmark)
class LeaseExtensionObservationScopeBenchmark {

    @Param("no-observer", "global", "scoped-match", "scoped-mismatch")
    lateinit var observationMode: String

    private lateinit var userElector: LeaderElector
    private lateinit var suspendUserElector: SuspendLeaderElector
    private lateinit var watchdogElector: LeaderElector
    private var globalRegistration: AutoCloseable? = null
    private var scope: LeaderLeaseExtensionObservationScope? = null

    @Setup
    fun setup() {
        when (observationMode) {
            "no-observer" -> Unit
            "global" -> globalRegistration = LeaderLeaseExtensionObservers.addObserver { }
            "scoped-match", "scoped-mismatch" -> {
                scope = LeaderLeaseExtensionObservers.addScopedObserver { }
            }
            else -> error("Unsupported observation mode: $observationMode")
        }
        val userOptions = LeaderElectionOptions(waitTime = 0.milliseconds, leaseTime = 30.seconds)
        userElector = LocalLeaderElector(userOptions)
        suspendUserElector = LocalSuspendLeaderElector(userOptions)
        watchdogElector = LocalLeaderElector(
            userOptions.copy(
                leaseTime = 90.milliseconds,
                autoExtend = true,
            )
        )
    }

    @TearDown
    fun tearDown() {
        globalRegistration?.close()
        scope?.close()
    }

    @Benchmark
    fun userBlocking(blackhole: Blackhole) {
        blackhole.consume(
            withObservationScope {
                userElector.runIfLeader("issue741-user") {
                    LockExtender.extendActiveLockDetailed(1.seconds)
                }
            },
        )
    }

    @Benchmark
    fun userSuspend(blackhole: Blackhole) = runBlocking(observationContext()) {
        blackhole.consume(
            suspendUserElector.runIfLeader("issue741-user-suspend") {
                LockExtender.extendActiveLockDetailedSuspend(1.seconds)
            },
        )
    }

    @Benchmark
    fun userMono(blackhole: Blackhole) {
        blackhole.consume(
            mono(context = observationContext()) {
                suspendUserElector.runIfLeader("issue741-user-mono") {
                    LockExtender.extendActiveLockDetailedSuspend(1.seconds)
                }
            }.block(),
        )
    }

    @Benchmark
    fun userFlux(blackhole: Blackhole) {
        blackhole.consume(
            flux(context = observationContext()) {
                send(
                    suspendUserElector.runIfLeader("issue741-user-flux") {
                        LockExtender.extendActiveLockDetailedSuspend(1.seconds)
                    },
                )
            }.blockLast(),
        )
    }

    @Benchmark
    fun userFlow(blackhole: Blackhole) = runBlocking {
        blackhole.consume(
            flow {
                emit(
                    suspendUserElector.runIfLeader("issue741-user-flow") {
                        LockExtender.extendActiveLockDetailedSuspend(1.seconds)
                    },
                )
            }.flowOn(observationContext()).single(),
        )
    }

    @Benchmark
    fun watchdogBlocking(blackhole: Blackhole) {
        blackhole.consume(
            withObservationScope {
                watchdogElector.runIfLeader("issue741-watchdog") {
                    Thread.sleep(WATCHDOG_ACTION_MILLIS)
                    1
                }
            },
        )
    }

    private fun observationContext(): CoroutineContext =
        if (observationMode == "scoped-match") {
            scope!!.asContextElement()
        } else {
            EmptyCoroutineContext
        }

    private fun <T> withObservationScope(block: () -> T): T =
        if (observationMode == "scoped-match") {
            scope!!.withScope(block)
        } else {
            block()
        }

    private companion object {
        private const val WATCHDOG_ACTION_MILLIS = 40L
    }
}
