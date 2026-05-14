package io.bluetape4k.leader.spring.aop

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.annotation.LeaderGroupElection
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.leader.spring.aop.util.LockNameValidator
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
 * AC-2 (group): `@LeaderGroupElection` reentrant — 동일 lockName Real handle 보유 시 backend acquire 미호출.
 *
 * [LeaderElectionAspectReentrantTest] 의 group 변형. Group aspect 도 sync 분기에서 동일 패턴 적용.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderGroupElectionAspectReentrantTest {

    companion object {
        private const val LOCK_NAME = "group-reentrant-job"
        private const val FACTORY_BEAN = "testGroupFactory"
        private const val SAMPLE_RESULT = "group-body-result"
        private const val MAX_LEADERS = 2
    }

    private interface GroupService {
        fun run(): String?
    }

    private class GroupServiceImpl : GroupService {
        @LeaderGroupElection(name = LOCK_NAME, maxLeaders = MAX_LEADERS)
        override fun run(): String? = SAMPLE_RESULT
    }

    private val election: LeaderGroupElector = mockk(relaxed = true)
    private val factoryMock: LeaderGroupElectorFactory = mockk()
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

    private fun newAspect(): LeaderGroupElectionAspect {
        every { factoryMock.create(any<LeaderGroupElectionOptions>()) } returns election
        every { beanSelector.selectGroupElectionFactory(any(), any()) } returns
            LeaderBeanSelector.Selected(FACTORY_BEAN, factoryMock)
        return LeaderGroupElectionAspect(
            beanSelector = beanSelector,
            props = LeaderAopProperties(),
            spel = SpelExpressionEvaluator(embeddedValueResolver = { it }, allowMethodInvocation = false),
            lockNameValidator = LockNameValidator(prefix = ""),
            recorders = emptyList(),
        )
    }

    @Test
    fun `group reentrant - 동일 lockName Real handle 보유 중이면 backend acquire 미호출 (AC-2)`() {
        val target = GroupServiceImpl()
        val method = GroupService::class.java.getDeclaredMethod("run")
        configureJoinPoint(method, target)
        val aspect = newAspect()

        // Simulate outer scope — group Real handle 같은 lockName
        val syntheticHandle = AopScopeAccess.createSyntheticGroupReal(
            lockName = LOCK_NAME,
            factoryBeanName = FACTORY_BEAN,
            maxLeaders = MAX_LEADERS,
        )

        val result = AopScopeAccess.withPushedSync(syntheticHandle) {
            aspect.aroundLeader(pjp)
        }

        result shouldBeEqualTo SAMPLE_RESULT
        verify(exactly = 0) { election.runIfLeaderResult(any<String>(), any<() -> Any?>()) }
    }

    @Test
    fun `group reentrant - 동일 lockName Real handle 없으면 backend acquire 정상 호출`() {
        val target = GroupServiceImpl()
        val method = GroupService::class.java.getDeclaredMethod("run")
        configureJoinPoint(method, target)
        val aspect = newAspect()

        every { election.runIfLeaderResult(any<String>(), any<() -> Any?>()) } answers {
            @Suppress("UNCHECKED_CAST")
            LeaderRunResult.Elected((secondArg<() -> Any?>()).invoke())
        }

        val result = aspect.aroundLeader(pjp)

        result shouldBeEqualTo SAMPLE_RESULT
        verify(exactly = 1) { election.runIfLeaderResult(any<String>(), any<() -> Any?>()) }
    }

    @Test
    fun `group reentrant - FailOpen sentinel 은 Real 아님 → backend acquire 정상 호출`() {
        val target = GroupServiceImpl()
        val method = GroupService::class.java.getDeclaredMethod("run")
        configureJoinPoint(method, target)
        val aspect = newAspect()

        every { election.runIfLeaderResult(any<String>(), any<() -> Any?>()) } answers {
            @Suppress("UNCHECKED_CAST")
            LeaderRunResult.Elected((secondArg<() -> Any?>()).invoke())
        }

        val identity = LockIdentity(
            lockName = LOCK_NAME,
            kind = LockIdentity.AnnotationKind.GROUP,
            factoryBeanName = FACTORY_BEAN,
            groupParams = LockIdentity.GroupParams(maxLeaders = MAX_LEADERS),
        )
        val failOpen = AopScopeAccess.createFailOpen(identity)
        val result = AopScopeAccess.withPushedSync(failOpen) {
            aspect.aroundLeader(pjp)
        }

        result shouldBeEqualTo SAMPLE_RESULT
        verify(exactly = 1) { election.runIfLeaderResult(any<String>(), any<() -> Any?>()) }
    }

    @Test
    fun `group reentrant - 다른 lockName Real handle 보유 시 backend acquire 호출`() {
        val target = GroupServiceImpl()
        val method = GroupService::class.java.getDeclaredMethod("run")
        configureJoinPoint(method, target)
        val aspect = newAspect()

        every { election.runIfLeaderResult(any<String>(), any<() -> Any?>()) } answers {
            @Suppress("UNCHECKED_CAST")
            LeaderRunResult.Elected((secondArg<() -> Any?>()).invoke())
        }

        // 다른 lockName 의 Real handle — same group factory
        val otherHandle = AopScopeAccess.createSyntheticGroupReal(
            lockName = "other-group-job",
            factoryBeanName = FACTORY_BEAN,
            maxLeaders = MAX_LEADERS,
        )
        val result = AopScopeAccess.withPushedSync(otherHandle) {
            aspect.aroundLeader(pjp)
        }

        result shouldBeEqualTo SAMPLE_RESULT
        verify(exactly = 1) { election.runIfLeaderResult(any<String>(), any<() -> Any?>()) }
    }
}
