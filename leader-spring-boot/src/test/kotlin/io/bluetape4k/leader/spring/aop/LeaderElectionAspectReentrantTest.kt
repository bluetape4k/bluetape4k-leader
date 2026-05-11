package io.bluetape4k.leader.spring.aop

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.annotation.LeaderElection
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.leader.spring.aop.util.LockNameValidator
import io.bluetape4k.assertions.shouldBeEqualTo
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.reflect.MethodSignature
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * AC-2: `@LeaderElection` reentrant — 동일 lockName 이미 보유 중이면 backend acquire 미호출.
 *
 * AopScopeAccess.withPushedSync + createSyntheticReal 로 "outer scope already holds the lock" 시뮬레이션.
 *
 * 검증:
 * - reentrant path: `election.runIfLeaderResult` 호출 0회 (backend acquire counter = 0)
 * - body 정상 실행 + 반환값 전달
 * - fail-open sentinel(FailOpen) 을 push 한 경우: outer scope 가 FailOpen → reentrant short-circuit 미적용 (Real 만 passthrough)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderElectionAspectReentrantTest {

    companion object {
        private const val LOCK_NAME = "reentrant-job"
        private const val FACTORY_BEAN = "testFactory"
        private const val SAMPLE_RESULT = "body-result"
    }

    private interface SampleService {
        fun run(): String?
    }

    private class SampleServiceImpl : SampleService {
        @LeaderElection(name = LOCK_NAME)
        override fun run(): String? = SAMPLE_RESULT
    }

    private val election: LeaderElector = mockk(relaxed = true)
    private val factoryMock: LeaderElectorFactory = mockk()
    private val beanSelector: LeaderBeanSelector = mockk()
    private val signature: MethodSignature = mockk()
    private val pjp: ProceedingJoinPoint = mockk()

    @BeforeEach
    fun setUp() {
        clearMocks(election, factoryMock, beanSelector, signature, pjp)
    }

    private fun configureJoinPoint(method: java.lang.reflect.Method, target: Any) {
        every { signature.method } returns method
        every { pjp.signature } returns signature
        every { pjp.target } returns target
        every { pjp.args } returns emptyArray()
        every { pjp.proceed() } returns SAMPLE_RESULT
    }

    private fun newAspect(): LeaderElectionAspect {
        every { factoryMock.create(any<LeaderElectionOptions>()) } returns election
        every { beanSelector.selectElectionFactory(any(), any()) } returns
            LeaderBeanSelector.Selected(FACTORY_BEAN, factoryMock)
        return LeaderElectionAspect(
            beanSelector = beanSelector,
            props = LeaderAopProperties(),
            spel = SpelExpressionEvaluator(embeddedValueResolver = { it }, allowMethodInvocation = false),
            lockNameValidator = LockNameValidator(prefix = ""),
            recorders = emptyList(),
        )
    }

    @Test
    fun `reentrant - 동일 lockName Real handle 보유 중이면 backend acquire 미호출 (AC-2)`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("run")
        configureJoinPoint(method, target)
        val aspect = newAspect()

        // Simulate outer scope: push a Real handle for the same lockName
        val syntheticHandle = AopScopeAccess.createSyntheticReal(LOCK_NAME, FACTORY_BEAN)
        val result = AopScopeAccess.withPushedSync(syntheticHandle) {
            // inner call: aspect should short-circuit without calling backend
            aspect.aroundLeader(pjp)
        }

        result shouldBeEqualTo SAMPLE_RESULT
        // The elector's runIfLeaderResult must NOT be called (reentrant short-circuit)
        verify(exactly = 0) { election.runIfLeaderResult<Any?>(any(), any()) }
    }

    @Test
    fun `reentrant - 동일 lockName Real handle 없으면 backend acquire 정상 호출`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("run")
        configureJoinPoint(method, target)
        val aspect = newAspect()

        every { election.runIfLeaderResult<Any?>(any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            LeaderRunResult.Elected((secondArg<() -> Any?>()).invoke())
        }

        // No outer scope — normal path
        val result = aspect.aroundLeader(pjp)

        result shouldBeEqualTo SAMPLE_RESULT
        // Backend is called exactly once
        verify(exactly = 1) { election.runIfLeaderResult<Any?>(any(), any()) }
    }

    @Test
    fun `reentrant - FailOpen sentinel 보유 중이면 reentrant short-circuit 미적용 (FailOpen 은 Real 아님)`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("run")
        configureJoinPoint(method, target)
        val aspect = newAspect()

        every { election.runIfLeaderResult<Any?>(any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            LeaderRunResult.Elected((secondArg<() -> Any?>()).invoke())
        }

        // Simulate outer scope with FailOpen sentinel (not Real)
        val identity = io.bluetape4k.leader.LockIdentity(
            lockName = LOCK_NAME,
            kind = io.bluetape4k.leader.LockIdentity.AnnotationKind.SINGLE,
            factoryBeanName = FACTORY_BEAN,
        )
        val failOpen = AopScopeAccess.createFailOpen(identity)
        val result = AopScopeAccess.withPushedSync(failOpen) {
            // FailOpen in stack — Real check fails → backend IS called
            aspect.aroundLeader(pjp)
        }

        result shouldBeEqualTo SAMPLE_RESULT
        // Backend IS called: FailOpen does not count as reentrant Real
        verify(exactly = 1) { election.runIfLeaderResult<Any?>(any(), any()) }
    }

    @Test
    fun `reentrant - 다른 lockName Real handle 보유 시 reentrant short-circuit 미적용`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("run")
        configureJoinPoint(method, target)
        val aspect = newAspect()

        every { election.runIfLeaderResult<Any?>(any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            LeaderRunResult.Elected((secondArg<() -> Any?>()).invoke())
        }

        // Push a handle for a DIFFERENT lockName
        val otherHandle = AopScopeAccess.createSyntheticReal("other-job", FACTORY_BEAN)
        val result = AopScopeAccess.withPushedSync(otherHandle) {
            aspect.aroundLeader(pjp)
        }

        result shouldBeEqualTo SAMPLE_RESULT
        // Backend IS called: lockName mismatch
        verify(exactly = 1) { election.runIfLeaderResult<Any?>(any(), any()) }
    }
}
