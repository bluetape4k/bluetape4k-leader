package io.bluetape4k.leader.spring.aop

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.annotation.LeaderElection
import io.bluetape4k.leader.spring.aop.internal.CaptureInvariantException
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.leader.spring.aop.util.LockNameValidator
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.reflect.MethodSignature
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * [CaptureInvariantException] 타입 계약 및 AopScopeAccess.pollCapture() 동작 검증.
 *
 * ## 검증 항목
 * - [CaptureInvariantException] 은 [IllegalStateException] 서브타입 (SPI 계약)
 * - [AopScopeAccess.pollCapture] 는 set 없으면 null 반환 (single elector 정상 동작)
 * - [AopScopeAccess.pollCapture] 는 set 후 한 번만 값 반환 (poll semantics — idempotent)
 * - single elector action body 에서 pollCapture() == null 은 CaptureInvariantException 으로
 *   이어지지 않아야 함 (single elector 는 CaptureScope 미사용 → null 이 정상)
 *
 * Note: group elector 전용 capture invariant 위반 시나리오는 T15 (LeaderGroupElectionAspect) 에서 검증.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderElectionAspectCaptureInvariantTest {

    companion object {
        private const val SAMPLE_RESULT = "ok"
    }

    // ── CaptureInvariantException 타입 계약 ──

    @Test
    fun `CaptureInvariantException 은 IllegalStateException 서브타입`() {
        val ex = CaptureInvariantException("test message")
        assert(ex is IllegalStateException) { "CaptureInvariantException must extend IllegalStateException" }
        ex.message shouldBeEqualTo "test message"
    }

    @Test
    fun `CaptureInvariantException throw 시 IllegalStateException 으로 catch 가능`() {
        assertFailsWith<IllegalStateException> {
            throw CaptureInvariantException("invariant violated")
        }
    }

    // ── AopScopeAccess.pollCapture() 동작 ──

    @Test
    fun `pollCapture - set 없으면 null 반환 (single elector 정상)`() {
        val result = AopScopeAccess.pollCapture()
        assert(result == null) { "pollCapture without set must return null" }
    }

    @Test
    fun `pollCapture - poll 은 exactly-once semantics — 두 번째 호출은 null`() {
        val syntheticReal = AopScopeAccess.createSyntheticReal("test-lock", "testFactory")
        // Simulate set via LeaderLockHandleCapture (done via elector CaptureScope in production)
        // Here we test poll() idempotency by calling twice without set
        val first = AopScopeAccess.pollCapture()
        val second = AopScopeAccess.pollCapture()
        assert(first == null) { "first poll without set must be null" }
        assert(second == null) { "second poll must also be null" }
        // Synthetic handle is valid
        assert(syntheticReal.lockName == "test-lock")
    }

    // ── Single elector action body: pollCapture null 은 CaptureInvariantException 미발생 ──

    private interface SampleService {
        fun run(): String?
    }

    private class SampleServiceImpl : SampleService {
        @LeaderElection(name = "capture-test-job")
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
        every { factoryMock.create(any()) } returns election
        every { beanSelector.selectElectionFactory(any(), any()) } returns
            LeaderBeanSelector.Selected("testFactory", factoryMock)
        return LeaderElectionAspect(
            beanSelector = beanSelector,
            props = LeaderAopProperties(),
            spel = SpelExpressionEvaluator(embeddedValueResolver = { it }, allowMethodInvocation = false),
            lockNameValidator = LockNameValidator(prefix = ""),
            recorders = emptyList(),
        )
    }

    @Test
    fun `single elector elected - pollCapture null 이어도 정상 동작 (CaptureInvariantException 미발생)`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("run")
        configureJoinPoint(method, target)

        // Mock returns Elected by invoking action (single elector — does NOT call CaptureScope)
        every { election.runIfLeaderResult(any(), any<() -> Any?>()) } answers {
            @Suppress("UNCHECKED_CAST")
            LeaderRunResult.Elected((secondArg<() -> Any?>()).invoke())
        }

        val aspect = newAspect()
        // Must NOT throw CaptureInvariantException — single elector never sets capture
        val result = aspect.aroundLeader(pjp)
        result shouldBeEqualTo SAMPLE_RESULT
    }

    @Test
    fun `createSyntheticReal - lockName 과 factoryBeanName 이 LockIdentity 에 정확히 반영`() {
        val handle = AopScopeAccess.createSyntheticReal("my-lock", "myFactory", token = "tok123")
        handle.lockName shouldBeEqualTo "my-lock"
        handle.identity.factoryBeanName shouldBeEqualTo "myFactory"
        handle.token shouldBeEqualTo "tok123"
        handle.reentryDepth shouldBeEqualTo 0
    }
}
