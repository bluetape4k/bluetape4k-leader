package io.bluetape4k.leader.spring.aop

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import io.bluetape4k.leader.annotation.LeaderElection
import io.bluetape4k.leader.coroutines.LeaderElectionInfo
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.leader.spring.aop.util.LockNameValidator
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.reflect.MethodSignature
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import reactor.core.publisher.Flux
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderElectionAspectStreamTest {

    private interface StreamService {
        fun fluxBounded(): Flux<String>
        fun fluxAutoExtend(): Flux<String>
        fun fluxFailOpen(): Flux<String>
        fun fluxInvalid(): Flux<String>
        fun flowBounded(): Flow<String?>
        fun flowFailOpen(): Flow<String>
        fun flowInvalid(): Flow<String>
    }

    private class StreamServiceImpl : StreamService {
        @LeaderElection(name = "flux-bounded", streamBounded = true)
        override fun fluxBounded(): Flux<String> = Flux.empty()

        @LeaderElection(name = "flux-auto", leaseTime = "PT0.15S", autoExtend = true)
        override fun fluxAutoExtend(): Flux<String> = Flux.empty()

        @LeaderElection(
            name = "flux-fail-open",
            streamBounded = true,
            failureMode = LeaderAspectFailureMode.FAIL_OPEN_RUN,
        )
        override fun fluxFailOpen(): Flux<String> = Flux.empty()

        @LeaderElection(name = "flux-invalid")
        override fun fluxInvalid(): Flux<String> = Flux.empty()

        @LeaderElection(name = "flow-bounded", streamBounded = true)
        override fun flowBounded(): Flow<String?> = flowOf()

        @LeaderElection(
            name = "flow-fail-open",
            streamBounded = true,
            failureMode = LeaderAspectFailureMode.FAIL_OPEN_RUN,
        )
        override fun flowFailOpen(): Flow<String> = flowOf()

        @LeaderElection(name = "flow-invalid")
        override fun flowInvalid(): Flow<String> = flowOf()
    }

    private class CountingSuspendElector(
        private val elected: Boolean = true,
    ) : SuspendLeaderElector {
        val acquireCount = AtomicInteger()
        val releaseCount = AtomicInteger()

        override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
            if (!elected) return null
            acquireCount.incrementAndGet()
            val handle = AopScopeAccess.createSyntheticReal(lockName, "testSuspendFactory")
            return try {
                kotlinx.coroutines.withContext(AopScopeAccess.createLockHandleElement(handle)) {
                    action()
                }
            } finally {
                releaseCount.incrementAndGet()
            }
        }

        override fun state(lockName: String) =
            io.bluetape4k.leader.LeaderState.empty(lockName)
    }

    private class BackendErrorSuspendElector(
        private val error: Exception,
    ) : SuspendLeaderElector {
        override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? = throw error
        override fun state(lockName: String) = io.bluetape4k.leader.LeaderState.empty(lockName)
    }

    private val factoryMock: LeaderElectorFactory = mockk()
    private val beanSelector: LeaderBeanSelector = mockk()
    private val signature: MethodSignature = mockk()
    private val pjp: ProceedingJoinPoint = mockk()

    @BeforeEach
    fun setUp() {
        clearMocks(factoryMock, beanSelector, signature, pjp)
        every { beanSelector.selectElectionFactory(any(), any()) } returns
            LeaderBeanSelector.Selected("testFactory", factoryMock)
    }

    private fun newAspect(factory: SuspendLeaderElectorFactory): LeaderElectionAspect {
        every { beanSelector.selectSuspendElectorFactory(any(), any()) } returns
            LeaderBeanSelector.Selected("testSuspendFactory", factory)
        return LeaderElectionAspect(
            beanSelector = beanSelector,
            props = LeaderAopProperties(),
            spel = SpelExpressionEvaluator(embeddedValueResolver = { it }, allowMethodInvocation = false),
            lockNameValidator = LockNameValidator(prefix = ""),
            recorders = emptyList(),
        )
    }

    private fun fakeFactory(elector: SuspendLeaderElector): SuspendLeaderElectorFactory =
        SuspendLeaderElectorFactory { _ -> elector }

    private fun configureJoinPoint(methodName: String) {
        val method = StreamService::class.java.getDeclaredMethod(methodName)
        every { signature.method } returns method
        every { pjp.signature } returns signature
        every { pjp.target } returns StreamServiceImpl()
        every { pjp.args } returns emptyArray()
    }

    @Test
    fun `flux elected success keeps lock through stream completion`() {
        configureJoinPoint("fluxBounded")
        every { pjp.proceed() } returns Flux.just("a", "b")
        val elector = CountingSuspendElector()

        val aspect = newAspect(fakeFactory(elector))
        val values = (aspect.aroundLeader(pjp) as Flux<*>).collectList().block()

        values shouldBeEqualTo listOf("a", "b")
        elector.acquireCount.get() shouldBeEqualTo 1
        elector.releaseCount.get() shouldBeEqualTo 1
    }

    @Test
    fun `flux skip returns empty stream without body call`() {
        configureJoinPoint("fluxBounded")
        val elector = CountingSuspendElector(elected = false)

        val aspect = newAspect(fakeFactory(elector))
        val values = (aspect.aroundLeader(pjp) as Flux<*>).collectList().block()

        values shouldBeEqualTo emptyList<Any>()
        elector.acquireCount.get() shouldBeEqualTo 0
        elector.releaseCount.get() shouldBeEqualTo 0
    }

    @Test
    fun `flux fail open executes body when not elected`() {
        configureJoinPoint("fluxFailOpen")
        every { pjp.proceed() } returns Flux.just("fail-open")

        val aspect = newAspect(fakeFactory(CountingSuspendElector(elected = false)))
        val values = (aspect.aroundLeader(pjp) as Flux<*>).collectList().block()

        values shouldBeEqualTo listOf("fail-open")
    }

    @Test
    fun `flux fail open body error propagates raw exception`() {
        configureJoinPoint("fluxFailOpen")
        val bodyEx = IllegalStateException("flux fail-open body failed")
        every { pjp.proceed() } returns Flux.error<String>(bodyEx)

        val aspect = newAspect(fakeFactory(CountingSuspendElector(elected = false)))
        val flux = aspect.aroundLeader(pjp) as Flux<*>

        val thrown = assertFailsWith<IllegalStateException> { flux.collectList().block() }
        thrown.message shouldBeEqualTo bodyEx.message
    }

    @Test
    fun `flux backend error rethrows leader exception`() {
        configureJoinPoint("fluxBounded")
        val aspect = newAspect(fakeFactory(BackendErrorSuspendElector(RuntimeException("backend down"))))

        val flux = aspect.aroundLeader(pjp) as Flux<*>

        assertFailsWith<LeaderElectionException> { flux.collectList().block() }
    }

    @Test
    fun `flux body error propagates raw exception and releases lock`() {
        configureJoinPoint("fluxBounded")
        val bodyEx = IllegalStateException("flux body failed")
        every { pjp.proceed() } returns Flux.error<String>(bodyEx)
        val elector = CountingSuspendElector()

        val aspect = newAspect(fakeFactory(elector))
        val flux = aspect.aroundLeader(pjp) as Flux<*>

        val thrown = assertFailsWith<IllegalStateException> { flux.collectList().block() }
        thrown shouldBeEqualTo bodyEx
        elector.releaseCount.get() shouldBeEqualTo 1
    }

    @Test
    fun `flux dispose cancellation releases lock`() {
        configureJoinPoint("fluxBounded")
        val subscribed = CountDownLatch(1)
        every { pjp.proceed() } returns Flux.never<String>().doOnSubscribe { subscribed.countDown() }
        val elector = CountingSuspendElector()

        val aspect = newAspect(fakeFactory(elector))
        val disposable = (aspect.aroundLeader(pjp) as Flux<*>).subscribe()

        subscribed.await(2, TimeUnit.SECONDS).shouldBeTrue()
        disposable.dispose()
        eventually { elector.releaseCount.get() == 1 }.shouldBeTrue()
    }

    @Test
    fun `flux subscribes acquire independently per subscription`() {
        configureJoinPoint("fluxBounded")
        every { pjp.proceed() } returns Flux.just("x")
        val elector = CountingSuspendElector()
        val aspect = newAspect(fakeFactory(elector))
        val flux = aspect.aroundLeader(pjp) as Flux<*>

        flux.collectList().block()
        flux.collectList().block()

        elector.acquireCount.get() shouldBeEqualTo 2
        elector.releaseCount.get() shouldBeEqualTo 2
    }

    @Test
    fun `flux auto extend allows unbounded stream config`() {
        configureJoinPoint("fluxAutoExtend")
        every { pjp.proceed() } returns Flux.just("auto")
        val elector = CountingSuspendElector()
        val aspect = newAspect(fakeFactory(elector))

        val values = (aspect.aroundLeader(pjp) as Flux<*>).collectList().block()

        values shouldBeEqualTo listOf("auto")
        elector.acquireCount.get() shouldBeEqualTo 1
        elector.releaseCount.get() shouldBeEqualTo 1
    }

    @Test
    fun `invalid flux config fails at subscription time`() {
        configureJoinPoint("fluxInvalid")
        val aspect = newAspect(fakeFactory(CountingSuspendElector()))
        val flux = aspect.aroundLeader(pjp) as Flux<*>

        assertFailsWith<LeaderElectionException> { flux.collectList().block() }
    }

    @Test
    fun `flow elected success keeps lock and supports null element`() = runTest {
        configureJoinPoint("flowBounded")
        every { pjp.proceed() } returns flowOf("a", null, "b")
        val elector = CountingSuspendElector()

        val aspect = newAspect(fakeFactory(elector))
        val values = (aspect.aroundLeader(pjp) as Flow<*>).toList()

        values shouldBeEqualTo listOf("a", null, "b")
        elector.acquireCount.get() shouldBeEqualTo 1
        elector.releaseCount.get() shouldBeEqualTo 1
    }

    @Test
    fun `flow fail open executes body with not elected context`() = runTest {
        configureJoinPoint("flowFailOpen")
        every { pjp.proceed() } returns flow {
            emit(currentCoroutineContext()[LeaderElectionInfo]?.wasElected.toString())
        }

        val aspect = newAspect(fakeFactory(CountingSuspendElector(elected = false)))
        val values = (aspect.aroundLeader(pjp) as Flow<*>).toList()

        values shouldBeEqualTo listOf("false")
    }

    @Test
    fun `flow fail open body error propagates raw exception`() = runTest {
        configureJoinPoint("flowFailOpen")
        val bodyEx = IllegalStateException("flow fail-open body failed")
        every { pjp.proceed() } returns flow<String> { throw bodyEx }

        val aspect = newAspect(fakeFactory(CountingSuspendElector(elected = false)))
        val flow = aspect.aroundLeader(pjp) as Flow<*>

        val thrown = assertFailsWith<IllegalStateException> { flow.toList() }
        thrown.message shouldBeEqualTo bodyEx.message
    }

    @Test
    fun `flow body can assert lock in guarded collection`() = runTest {
        configureJoinPoint("flowBounded")
        every { pjp.proceed() } returns flow {
            LockAssert.assertLockedSuspend("flow-bounded")
            emit("locked")
        }

        val aspect = newAspect(fakeFactory(CountingSuspendElector()))
        val values = (aspect.aroundLeader(pjp) as Flow<*>).toList()

        values shouldBeEqualTo listOf("locked")
    }

    @Test
    fun `flow body error propagates raw exception and releases lock`() = runTest {
        configureJoinPoint("flowBounded")
        val bodyEx = IllegalStateException("flow body failed")
        every { pjp.proceed() } returns flow<String> { throw bodyEx }
        val elector = CountingSuspendElector()

        val aspect = newAspect(fakeFactory(elector))
        val flow = aspect.aroundLeader(pjp) as Flow<*>

        val thrown = assertFailsWith<IllegalStateException> { flow.toList() }
        thrown.message shouldBeEqualTo bodyEx.message
        elector.releaseCount.get() shouldBeEqualTo 1
    }

    @Test
    fun `invalid flow config fails at collection time`() = runTest {
        configureJoinPoint("flowInvalid")
        val aspect = newAspect(fakeFactory(CountingSuspendElector()))
        val flow = aspect.aroundLeader(pjp) as Flow<*>

        assertFailsWith<LeaderElectionException> { flow.toList() }
    }

    private fun eventually(block: () -> Boolean): Boolean {
        repeat(40) {
            if (block()) return true
            Thread.sleep(25)
        }
        return block()
    }
}
