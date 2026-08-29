package io.bluetape4k.leader.spring.aop

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseExtensionObservationScope
import io.bluetape4k.leader.LeaderLeaseExtensionObservers
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LockExtender
import io.bluetape4k.leader.annotation.LeaderElection
import io.bluetape4k.leader.annotation.LeaderGroupElection
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.leader.spring.aop.util.LockNameValidator
import io.bluetape4k.leader.spring.metrics.LeaseExtensionObservationScopeOwner
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.reflect.MethodSignature
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class LeaderLeaseExtensionObservationScopeAspectTest {

    private interface SingleService {
        @LeaderElection(name = "scope-sync")
        fun sync(): String

        @LeaderElection(name = "scope-suspend")
        suspend fun suspending(): String

        @LeaderElection(name = "scope-mono")
        fun mono(): Mono<String>

        @LeaderElection(name = "scope-flux", streamBounded = true)
        fun flux(): Flux<String>

        @LeaderElection(name = "scope-flow", streamBounded = true)
        fun flow(): Flow<String>
    }

    private interface GroupService {
        @LeaderGroupElection(name = "scope-group-sync", maxLeaders = 2)
        fun sync(): String

        @LeaderGroupElection(name = "scope-group-suspend", maxLeaders = 2)
        suspend fun suspending(): String

        @LeaderGroupElection(name = "scope-group-mono", maxLeaders = 2)
        fun mono(): Mono<String>
    }

    @Test
    fun `single AOP는 현재 context scope와 global observer에만 전달한다`() = runTest {
        withObservationScopes { first, second, global ->
            val aspect = newSingleAspect(first.owner)
            val pjp = singleJoinPoint("sync") {
                LockExtender.extendActiveLockDetailed(1.seconds)
                "ok"
            }

            aspect.aroundLeader(pjp) shouldBeEqualTo "ok"

            awaitCounts(first.count, second.count, global, 1, 0, 1)
        }
    }

    @Test
    fun `single suspend Mono Flux Flow는 context scope를 coroutine 경계까지 전파한다`() = runTest {
        withObservationScopes { first, second, global ->
            val aspect = newSingleAspect(first.owner)

            runSuspendAspect(aspect, singleJoinPoint("suspending") {
                LockExtender.extendActiveLockDetailed(1.seconds)
                "suspend"
            }) shouldBeEqualTo "suspend"

            val mono = singleJoinPoint("mono") {
                Mono.fromCallable {
                    LockExtender.extendActiveLockDetailed(1.seconds)
                    "mono"
                }
            }
            (aspect.aroundLeader(mono) as Mono<*>).block() shouldBeEqualTo "mono"

            val flux = singleJoinPoint("flux") {
                Flux.defer {
                    LockExtender.extendActiveLockDetailed(1.seconds)
                    Flux.just("flux")
                }
            }
            (aspect.aroundLeader(flux) as Flux<*>).collectList().block() shouldBeEqualTo listOf("flux")

            val flow = singleJoinPoint("flow") {
                flow {
                    LockExtender.extendActiveLockDetailed(1.seconds)
                    emit("flow")
                }
            }
            (aspect.aroundLeader(flow) as Flow<*>).toList() shouldBeEqualTo listOf("flow")

            awaitCounts(first.count, second.count, global, 4, 0, 4)
        }
    }

    @Test
    fun `group sync suspend Mono도 현재 context scope와 global observer에만 전달한다`() = runTest {
        withObservationScopes { first, second, global ->
            val aspect = newGroupAspect(first.owner)

            aspect.aroundLeader(groupJoinPoint("sync") {
                LockExtender.extendActiveLockDetailed(1.seconds)
                "sync"
            }) shouldBeEqualTo "sync"

            runSuspendGroupAspect(aspect, groupJoinPoint("suspending") {
                LockExtender.extendActiveLockDetailed(1.seconds)
                "suspend"
            }) shouldBeEqualTo "suspend"

            val mono = groupJoinPoint("mono") {
                Mono.fromCallable {
                    LockExtender.extendActiveLockDetailed(1.seconds)
                    "mono"
                }
            }
            (aspect.aroundLeader(mono) as Mono<*>).block() shouldBeEqualTo "mono"

            awaitCounts(first.count, second.count, global, 3, 0, 3)
        }
    }

    private fun newSingleAspect(owner: LeaseExtensionObservationScopeOwner): LeaderElectionAspect {
        val election = mockk<LeaderElector>()
        val action = slot<() -> Any?>()
        every { election.runIfLeaderResult<Any?>(any<String>(), capture(action)) } answers {
            LeaderRunResult.Elected(action.captured.invoke())
        }
        val suspendElection = object : SuspendLeaderElector {
            override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? = action()
        }
        val selector = mockk<LeaderBeanSelector>()
        every { selector.selectElectionFactory(any(), any()) } returns
            LeaderBeanSelector.Selected("singleFactory", LeaderElectorFactory { election })
        every { selector.selectSuspendElectorFactory(any(), any()) } returns
            LeaderBeanSelector.Selected("singleSuspendFactory", SuspendLeaderElectorFactory { suspendElection })
        return LeaderElectionAspect(
            beanSelector = selector,
            props = LeaderAopProperties(),
            spel = SpelExpressionEvaluator({ it }, false),
            lockNameValidator = LockNameValidator(""),
            recorders = emptyList(),
        ).apply { observationScopeOwner = owner }
    }

    private fun newGroupAspect(owner: LeaseExtensionObservationScopeOwner): LeaderGroupElectionAspect {
        val election = mockk<LeaderGroupElector>()
        val action = slot<() -> Any?>()
        every { election.runIfLeaderResult<Any?>(any<String>(), capture(action)) } answers {
            LeaderRunResult.Elected(action.captured.invoke())
        }
        val suspendElection = object : SuspendLeaderGroupElector {
            override val maxLeaders: Int = 2

            override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? = action()

            override fun activeCount(lockName: String): Int = 0

            override fun availableSlots(lockName: String): Int = maxLeaders

            override fun state(lockName: String): LeaderGroupState =
                LeaderGroupState(lockName, maxLeaders, activeCount = 0)
        }
        val selector = mockk<LeaderBeanSelector>()
        every { selector.selectGroupElectionFactory(any(), any()) } returns
            LeaderBeanSelector.Selected("groupFactory", LeaderGroupElectorFactory { election })
        every { selector.selectSuspendGroupElectorFactory(any(), any()) } returns
            LeaderBeanSelector.Selected("groupSuspendFactory", SuspendLeaderGroupElectorFactory { suspendElection })
        return LeaderGroupElectionAspect(
            beanSelector = selector,
            props = LeaderAopProperties(),
            spel = SpelExpressionEvaluator({ it }, false),
            lockNameValidator = LockNameValidator(""),
            recorders = emptyList(),
        ).apply { observationScopeOwner = owner }
    }

    private fun singleJoinPoint(methodName: String, proceed: () -> Any?): ProceedingJoinPoint =
        joinPoint(SingleService::class.java, methodName, proceed)

    private fun groupJoinPoint(methodName: String, proceed: () -> Any?): ProceedingJoinPoint =
        joinPoint(GroupService::class.java, methodName, proceed)

    private fun joinPoint(type: Class<*>, methodName: String, proceed: () -> Any?): ProceedingJoinPoint {
        val isSuspend = methodName == "suspending"
        val method = if (isSuspend) {
            type.getDeclaredMethod(methodName, Continuation::class.java)
        } else {
            type.getDeclaredMethod(methodName)
        }
        val signature = mockk<MethodSignature>()
        val pjp = mockk<ProceedingJoinPoint>()
        every { signature.method } returns method
        every { pjp.signature } returns signature
        every { pjp.target } returns mockk(relaxed = true)
        every { pjp.args } returns emptyArray()
        every { pjp.proceed() } answers { proceed() }
        every { pjp.proceed(any<Array<Any?>>()) } answers { proceed() }
        return pjp
    }

    private suspend fun runSuspendAspect(aspect: LeaderElectionAspect, pjp: ProceedingJoinPoint): Any? =
        suspendCancellableCoroutine { continuation ->
            every { pjp.args } returns arrayOf<Any?>(continuation)
            val result = aspect.aroundLeader(pjp)
            if (result !== COROUTINE_SUSPENDED) continuation.resume(result)
        }

    private suspend fun runSuspendGroupAspect(aspect: LeaderGroupElectionAspect, pjp: ProceedingJoinPoint): Any? =
        suspendCancellableCoroutine { continuation ->
            every { pjp.args } returns arrayOf<Any?>(continuation)
            val result = aspect.aroundLeader(pjp)
            if (result !== COROUTINE_SUSPENDED) continuation.resume(result)
        }

    private fun awaitCounts(
        first: AtomicInteger,
        second: AtomicInteger,
        global: AtomicInteger,
        expectedFirst: Int,
        expectedSecond: Int,
        expectedGlobal: Int,
    ) {
        await.atMost(5.seconds.toJavaDuration()).untilAsserted {
            first.get() shouldBeEqualTo expectedFirst
            second.get() shouldBeEqualTo expectedSecond
            global.get() shouldBeEqualTo expectedGlobal
        }
    }

    private suspend inline fun withObservationScopes(
        crossinline block: suspend (ScopeFixture, ScopeFixture, AtomicInteger) -> Unit,
    ) {
        val first = scopeFixture()
        val second = scopeFixture()
        val global = AtomicInteger()
        val globalHandle = LeaderLeaseExtensionObservers.addObserver { global.incrementAndGet() }
        try {
            LockExtender.extendActiveLockDetailed(1.seconds)
            awaitCounts(first.count, second.count, global, 0, 0, 1)
            global.set(0)
            block(first, second, global)
        } finally {
            globalHandle.close()
            first.close()
            second.close()
        }
    }

    private fun scopeFixture(): ScopeFixture {
        val count = AtomicInteger()
        val scope = LeaderLeaseExtensionObservers.addScopedObserver { count.incrementAndGet() }
        val owner = LeaseExtensionObservationScopeOwner(mockk(relaxed = true)).apply { activate(scope) }
        return ScopeFixture(count, scope, owner)
    }

    private data class ScopeFixture(
        val count: AtomicInteger,
        val scope: LeaderLeaseExtensionObservationScope,
        val owner: LeaseExtensionObservationScopeOwner,
    ) : AutoCloseable {
        override fun close() {
            owner.clear(scope)
            scope.close()
        }
    }
}
