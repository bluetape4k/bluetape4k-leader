package io.bluetape4k.leader.spring.aop

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import io.bluetape4k.leader.annotation.LeaderElection
import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.metrics.SkipReason
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.leader.spring.aop.util.LockNameValidator
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
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
 * 6-cell failure mode matrix 검증:
 *
 * | scenario         | RETHROW          | SKIP       | FAIL_OPEN_RUN          |
 * |------------------|------------------|------------|------------------------|
 * | Skipped(contention) | null (기본 동작) | null       | body 실행 + 결과 반환   |
 * | backend Exception | LeaderElectionException | null | body 실행 + 결과 반환   |
 *
 * 추가: FAIL_OPEN_RUN 분기에서 body 실행 시 `LockAssert.isLocked()` = false (FailOpen sentinel).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderElectionAspectFailureModeTest {

    companion object {
        private const val SAMPLE_RESULT = "ok"
    }

    // ── 테스트용 서비스 정의 ──

    private interface SampleService {
        fun runRethrow(): String?
        fun runSkip(): String?
        fun runFailOpen(): String?
    }

    private class SampleServiceImpl : SampleService {
        @LeaderElection(name = "rethrow-job", failureMode = LeaderAspectFailureMode.RETHROW)
        override fun runRethrow(): String? = SAMPLE_RESULT

        @LeaderElection(name = "skip-job", failureMode = LeaderAspectFailureMode.SKIP)
        override fun runSkip(): String? = SAMPLE_RESULT

        @LeaderElection(name = "fail-open-job", failureMode = LeaderAspectFailureMode.FAIL_OPEN_RUN)
        override fun runFailOpen(): String? = SAMPLE_RESULT
    }

    private val election: LeaderElector = mockk(relaxed = true)
    private val recorder: LeaderAopMetricsRecorder = mockk(relaxed = true)
    private val factoryMock: LeaderElectorFactory = mockk()
    private val beanSelector: LeaderBeanSelector = mockk()
    private val signature: MethodSignature = mockk()
    private val pjp: ProceedingJoinPoint = mockk()

    @BeforeEach
    fun setUp() {
        clearMocks(election, recorder, factoryMock, beanSelector, signature, pjp)
    }

    private fun configureJoinPoint(method: java.lang.reflect.Method, target: Any, args: Array<Any?> = emptyArray()) {
        every { signature.method } returns method
        every { pjp.signature } returns signature
        every { pjp.target } returns target
        every { pjp.args } returns args
    }

    private fun newAspect(recorders: List<LeaderAopMetricsRecorder> = emptyList()): LeaderElectionAspect {
        every { factoryMock.create(any()) } returns election
        every { beanSelector.selectElectionFactory(any(), any()) } returns
            LeaderBeanSelector.Selected("testFactory", factoryMock)
        return LeaderElectionAspect(
            beanSelector = beanSelector,
            props = LeaderAopProperties(),
            spel = SpelExpressionEvaluator(embeddedValueResolver = { it }, allowMethodInvocation = false),
            lockNameValidator = LockNameValidator(prefix = ""),
            recorders = recorders,
        )
    }

    // ── Row 1: Skipped (contention) ──

    @Test
    fun `Skipped × RETHROW - null 반환 (기본 skip-on-contention)`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runRethrow")
        configureJoinPoint(method, target)
        every { election.runIfLeaderResult(any(), any<() -> Any?>()) } returns LeaderRunResult.Skipped

        val aspect = newAspect(listOf(recorder))
        aspect.aroundLeader(pjp).shouldBeNull()

        verify(exactly = 1) { recorder.onLockNotAcquired("rethrow-job", any(), SkipReason.CONTENTION) }
    }

    @Test
    fun `Skipped × SKIP - null 반환`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSkip")
        configureJoinPoint(method, target)
        every { election.runIfLeaderResult(any(), any<() -> Any?>()) } returns LeaderRunResult.Skipped

        val aspect = newAspect(listOf(recorder))
        aspect.aroundLeader(pjp).shouldBeNull()

        verify(exactly = 1) { recorder.onLockNotAcquired("skip-job", any(), SkipReason.CONTENTION) }
    }

    @Test
    fun `Skipped × FAIL_OPEN_RUN - body 실행 후 결과 반환 + FAIL_OPEN_FORCED 메트릭`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runFailOpen")
        configureJoinPoint(method, target)
        every { pjp.proceed() } returns SAMPLE_RESULT
        every { election.runIfLeaderResult(any(), any<() -> Any?>()) } returns LeaderRunResult.Skipped

        val aspect = newAspect(listOf(recorder))
        val result = aspect.aroundLeader(pjp)

        result shouldBeEqualTo SAMPLE_RESULT
        verify(exactly = 1) { recorder.onLockNotAcquired("fail-open-job", any(), SkipReason.FAIL_OPEN_FORCED) }
        verify(exactly = 1) { recorder.onTaskStarted("fail-open-job") }
        verify(exactly = 1) { recorder.onTaskFinished("fail-open-job", any()) }
    }

    @Test
    fun `Skipped × FAIL_OPEN_RUN - body 실행 시 LockAssert_isLocked 는 false (FailOpen sentinel)`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runFailOpen")
        configureJoinPoint(method, target)
        every { election.runIfLeaderResult(any(), any<() -> Any?>()) } returns LeaderRunResult.Skipped

        var isLockedInsideBody = true  // default true to detect if not set
        every { pjp.proceed() } answers {
            isLockedInsideBody = LockAssert.isLocked()
            SAMPLE_RESULT
        }

        val aspect = newAspect()
        aspect.aroundLeader(pjp)

        isLockedInsideBody shouldBeEqualTo false  // FailOpen sentinel → not locked
    }

    // ── Row 2: backend Exception ──

    @Test
    fun `BackendError × RETHROW - LeaderElectionException wrapping + lockName 포함`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runRethrow")
        configureJoinPoint(method, target)
        val backendEx = RuntimeException("redis-prod-01:6379 timeout")
        every { election.runIfLeaderResult(any(), any<() -> Any?>()) } throws backendEx

        val aspect = newAspect(listOf(recorder))
        val wrapped = assertFailsWith<LeaderElectionException> { aspect.aroundLeader(pjp) }

        wrapped.cause shouldBeEqualTo backendEx
        wrapped.message!!.contains("rethrow-job") shouldBeEqualTo true
        // host info must NOT leak (R-33)
        wrapped.message!!.contains("redis-prod-01") shouldBeEqualTo false

        verify(exactly = 1) { recorder.onLockNotAcquired("rethrow-job", any(), SkipReason.BACKEND_ERROR) }
    }

    @Test
    fun `BackendError × SKIP - null 반환 + BACKEND_ERROR 메트릭`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSkip")
        configureJoinPoint(method, target)
        val backendEx = RuntimeException("backend down")
        every { election.runIfLeaderResult(any(), any<() -> Any?>()) } throws backendEx

        val aspect = newAspect(listOf(recorder))
        aspect.aroundLeader(pjp).shouldBeNull()

        verify(exactly = 1) { recorder.onLockNotAcquired("skip-job", any(), SkipReason.BACKEND_ERROR) }
    }

    @Test
    fun `BackendError × FAIL_OPEN_RUN - body 실행 후 결과 반환 + FAIL_OPEN_FORCED 메트릭`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runFailOpen")
        configureJoinPoint(method, target)
        every { pjp.proceed() } returns SAMPLE_RESULT
        val backendEx = RuntimeException("redis down")
        every { election.runIfLeaderResult(any(), any<() -> Any?>()) } throws backendEx

        val aspect = newAspect(listOf(recorder))
        val result = aspect.aroundLeader(pjp)

        result shouldBeEqualTo SAMPLE_RESULT
        verify(exactly = 1) { recorder.onLockNotAcquired("fail-open-job", any(), SkipReason.FAIL_OPEN_FORCED) }
        verify(exactly = 1) { recorder.onTaskStarted("fail-open-job") }
        verify(exactly = 1) { recorder.onTaskFinished("fail-open-job", any()) }
    }

    @Test
    fun `BackendError × FAIL_OPEN_RUN - body throw 는 그대로 전파`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runFailOpen")
        configureJoinPoint(method, target)
        val bodyEx = IllegalStateException("body failure in fail-open")
        every { pjp.proceed() } throws bodyEx
        every { election.runIfLeaderResult(any(), any<() -> Any?>()) } throws RuntimeException("backend error")

        val aspect = newAspect()
        val ex = assertFailsWith<IllegalStateException> { aspect.aroundLeader(pjp) }
        ex shouldBeEqualTo bodyEx
    }

    @Test
    fun `BackendError × FAIL_OPEN_RUN - body 실행 시 LockAssert_isLocked 는 false (FailOpen sentinel)`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runFailOpen")
        configureJoinPoint(method, target)
        every { election.runIfLeaderResult(any(), any<() -> Any?>()) } throws RuntimeException("backend down")

        var isLockedInsideBody = true
        every { pjp.proceed() } answers {
            isLockedInsideBody = LockAssert.isLocked()
            SAMPLE_RESULT
        }

        val aspect = newAspect()
        aspect.aroundLeader(pjp)

        isLockedInsideBody shouldBeEqualTo false
    }

    // ── FailOpen scope handle cleanup ──

    @Test
    fun `FAIL_OPEN_RUN scope 종료 후 LockStateHolder 정리 - 다음 호출에 영향 없음`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runFailOpen")
        configureJoinPoint(method, target)
        every { pjp.proceed() } returns SAMPLE_RESULT
        every { election.runIfLeaderResult(any(), any<() -> Any?>()) } returns LeaderRunResult.Skipped

        val aspect = newAspect()
        aspect.aroundLeader(pjp)  // first call — FAIL_OPEN_RUN body executed

        // After the call, LockStateHolder must be clean
        AopScopeAccess.peekSyncMatching("fail-open-job").shouldBeNull()
    }
}
