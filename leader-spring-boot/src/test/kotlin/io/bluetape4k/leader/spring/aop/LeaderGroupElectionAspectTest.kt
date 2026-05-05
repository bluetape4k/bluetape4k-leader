package io.bluetape4k.leader.spring.aop

import io.bluetape4k.leader.LeaderGroupElectionException
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import io.bluetape4k.leader.annotation.LeaderGroupElection
import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.metrics.SkipReason
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.leader.spring.aop.util.LockNameValidator
import io.bluetape4k.logging.KLogging
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldContain
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.reflect.MethodSignature
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CancellationException

/**
 * [LeaderGroupElectionAspect] 통합 테스트 (#95):
 * - 정상 elected → 본문 결과 반환 + onTaskFinished
 * - 미선출 → null 반환 + onLockNotAcquired(CONTENTION)
 * - 본문 throw → wrapping 없이 그대로 전파 [Rel-1]
 * - backend throw → LeaderGroupElectionException wrapping [Sec-3][R-33]
 * - CancellationException → 우선 재throw [Rel-2]
 * - SKIP 모드 → backend throw 흡수 후 null
 * - metrics fast-path — recorder 0개 시 fanOut 진입 없음
 * - Method-level cache — annotation lookup 호출당 1회만
 * - SpEL 평가 — lock name 동적 해석
 * - Fix-94: factory.create() I/O 실패 → failureMode 적용 회귀 테스트
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderGroupElectionAspectTest {

    companion object: KLogging() {
        private const val SAMPLE_RESULT = "ok"
    }

    private interface SampleService {
        fun runSync(): String?
        fun runWithArg(region: String): String?
    }

    private class SampleServiceImpl: SampleService {
        @LeaderGroupElection(name = "static-group-job", maxLeaders = 3)
        override fun runSync(): String? = SAMPLE_RESULT

        @LeaderGroupElection(name = "'r-' + #region", maxLeaders = 2)
        override fun runWithArg(region: String): String? = "result-$region"
    }

    // Class-level mocks — reused across all tests, cleared in @BeforeEach
    private val election: LeaderGroupElector = mockk(relaxed = true)
    private val recorder: LeaderAopMetricsRecorder = mockk(relaxed = true)
    private val signature: MethodSignature = mockk()
    private val pjp: ProceedingJoinPoint = mockk()
    private val factoryMock: LeaderGroupElectorFactory = mockk()
    private val beanSelector: LeaderBeanSelector = mockk()

    @BeforeEach
    fun setUp() {
        clearMocks(election, recorder, signature, pjp, factoryMock, beanSelector)
    }

    private fun configureJoinPoint(method: java.lang.reflect.Method, target: Any, args: Array<Any?>) {
        every { signature.method } returns method
        every { pjp.signature } returns signature
        every { pjp.target } returns target
        every { pjp.args } returns args
    }

    private fun newAspect(recorders: List<LeaderAopMetricsRecorder> = emptyList()): LeaderGroupElectionAspect {
        every { factoryMock.create(any()) } returns election
        every { beanSelector.selectGroupElectionFactory(any()) } returns
                LeaderBeanSelector.Selected("testGroupFactory", factoryMock)

        return LeaderGroupElectionAspect(
            beanSelector = beanSelector,
            props = LeaderAopProperties(),
            spel = SpelExpressionEvaluator(embeddedValueResolver = { it }, allowMethodInvocation = false),
            lockNameValidator = LockNameValidator(prefix = ""),
            recorders = recorders,
        )
    }

    @Test
    fun `elected - 본문 결과 반환 + onTaskFinished 호출`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")
        configureJoinPoint(method, target, emptyArray())
        every { pjp.proceed() } returns SAMPLE_RESULT

        val actionSlot = slot<() -> Any?>()
        every { election.runIfGroupLeaderResult<Any?>(any(), capture(actionSlot)) } answers { LeaderRunResult.Elected(actionSlot.captured.invoke()) }

        val aspect = newAspect(listOf(recorder))

        val result = aspect.aroundLeader(pjp)
        result shouldBeEqualTo SAMPLE_RESULT

        verify(exactly = 1) { recorder.onLockAttempt("static-group-job", any()) }
        verify(exactly = 1) { recorder.onLockAcquired("static-group-job", any(), any()) }
        verify(exactly = 1) { recorder.onTaskStarted("static-group-job") }
        verify(exactly = 1) { recorder.onTaskFinished("static-group-job", any()) }
    }

    @Test
    fun `미선출 - null 반환 + onLockNotAcquired(CONTENTION)`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")
        configureJoinPoint(method, target, emptyArray())

        every { election.runIfGroupLeaderResult<Any?>(any(), any()) } returns LeaderRunResult.Skipped

        val aspect = newAspect(listOf(recorder))

        aspect.aroundLeader(pjp).shouldBeNull()
        verify(exactly = 1) { recorder.onLockNotAcquired("static-group-job", any(), SkipReason.CONTENTION) }
    }

    @Test
    fun `본문 throw - wrapping 없이 그대로 전파 (Rel-1)`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")
        configureJoinPoint(method, target, emptyArray())
        val bodyEx = RuntimeException("body failure")
        every { pjp.proceed() } throws bodyEx

        val actionSlot = slot<() -> Any?>()
        every { election.runIfGroupLeaderResult<Any?>(any(), capture(actionSlot)) } answers { LeaderRunResult.Elected(actionSlot.captured.invoke()) }

        val aspect = newAspect()
        val ex = assertThrows<RuntimeException> { aspect.aroundLeader(pjp) }
        ex shouldBeEqualTo bodyEx
    }

    @Test
    fun `backend throw - LeaderGroupElectionException wrapping (Sec-3 R-33)`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")
        configureJoinPoint(method, target, emptyArray())

        val backendEx = RuntimeException("Connection to redis-prod-01.internal:6379 timeout")
        every { election.runIfGroupLeaderResult<Any?>(any(), any()) } throws backendEx

        val aspect = newAspect()
        val wrapped = assertThrows<LeaderGroupElectionException> { aspect.aroundLeader(pjp) }
        wrapped.cause shouldBeEqualTo backendEx
        wrapped.message!!.contains("redis-prod-01") shouldBeEqualTo false
        wrapped.message!! shouldContain "static-group-job"
    }

    @Test
    fun `CancellationException - 우선 재throw (Rel-2)`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")
        configureJoinPoint(method, target, emptyArray())
        val cancelEx = CancellationException("cancelled")
        every { pjp.proceed() } throws cancelEx

        val actionSlot = slot<() -> Any?>()
        every { election.runIfGroupLeaderResult<Any?>(any(), capture(actionSlot)) } answers { LeaderRunResult.Elected(actionSlot.captured.invoke()) }

        val aspect = newAspect()
        val ex = assertThrows<CancellationException> { aspect.aroundLeader(pjp) }
        ex shouldBeEqualTo cancelEx
    }

    @Test
    fun `SKIP 모드 - backend throw 흡수 후 null`() {
        class SampleSkip {
            @LeaderGroupElection(name = "skip-group-job", maxLeaders = 2, failureMode = LeaderAspectFailureMode.SKIP)
            fun run(): String? = SAMPLE_RESULT
        }

        val skipTarget = SampleSkip()
        val skipMethod = SampleSkip::class.java.getDeclaredMethod("run")
        configureJoinPoint(skipMethod, skipTarget, emptyArray())

        val backendEx = RuntimeException("backend down")
        every { election.runIfGroupLeaderResult<Any?>(any(), any()) } throws backendEx

        val aspect = newAspect()
        aspect.aroundLeader(pjp).shouldBeNull()
    }

    @Test
    fun `metrics fast-path - recorders 0개 시 fanOut 진입 없음`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")
        configureJoinPoint(method, target, emptyArray())
        every { pjp.proceed() } returns SAMPLE_RESULT

        val actionSlot = slot<() -> Any?>()
        every { election.runIfGroupLeaderResult<Any?>(any(), capture(actionSlot)) } answers { LeaderRunResult.Elected(actionSlot.captured.invoke()) }

        val aspect = newAspect(recorders = emptyList())
        aspect.aroundLeader(pjp) shouldBeEqualTo SAMPLE_RESULT
    }

    @Test
    fun `Method-level cache - annotation lookup 호출당 1회만`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")

        val actionSlot = slot<() -> Any?>()
        every { election.runIfGroupLeaderResult<Any?>(any(), capture(actionSlot)) } answers { LeaderRunResult.Elected(actionSlot.captured.invoke()) }
        every { factoryMock.create(any()) } returns election
        every { beanSelector.selectGroupElectionFactory(any()) } returns
                LeaderBeanSelector.Selected("testGroupFactory", factoryMock)

        val aspect = LeaderGroupElectionAspect(
            beanSelector = beanSelector,
            props = LeaderAopProperties(),
            spel = SpelExpressionEvaluator(embeddedValueResolver = { it }),
            lockNameValidator = LockNameValidator(),
            recorders = emptyList(),
        )

        repeat(100) {
            configureJoinPoint(method, target, emptyArray())
            every { pjp.proceed() } returns SAMPLE_RESULT
            aspect.aroundLeader(pjp)
        }

        verify(exactly = 1) { beanSelector.selectGroupElectionFactory(any()) }
    }

    @Test
    fun `SpEL 평가 - lock name 동적 해석`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runWithArg", String::class.java)
        configureJoinPoint(method, target, arrayOf("AP"))
        every { pjp.proceed() } returns "result-AP"

        val actionSlot = slot<() -> Any?>()
        val nameSlot = slot<String>()
        every {
            election.runIfGroupLeaderResult<Any?>(capture(nameSlot), capture(actionSlot))
        } answers { LeaderRunResult.Elected(actionSlot.captured.invoke()) }

        val aspect = newAspect()
        val result = aspect.aroundLeader(pjp)

        nameSlot.captured shouldBeEqualTo "r-AP"
        result shouldBeEqualTo "result-AP"
    }

    @Test
    fun `wrapped exception 은 LeaderGroupElectionException 인스턴스`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")
        configureJoinPoint(method, target, emptyArray())

        every { election.runIfGroupLeaderResult<Any?>(any(), any()) } throws IllegalStateException("backend")

        val aspect = newAspect()
        val ex = assertThrows<LeaderGroupElectionException> { aspect.aroundLeader(pjp) }
        ex shouldBeInstanceOf LeaderGroupElectionException::class
    }

    // ── Fix-94: factory.create() I/O 실패가 failureMode 우회하던 버그 회귀 테스트 ──

    @Test
    fun `Fix-94 - factory create 실패 RETHROW - LeaderGroupElectionException wrapping + host 누출 없음`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")
        configureJoinPoint(method, target, emptyArray())

        val backendEx = RuntimeException("Connection to redis-prod-01.internal:6379 refused")
        val aspect = newAspect()
        every { factoryMock.create(any()) } throws backendEx

        val wrapped = assertThrows<LeaderGroupElectionException> { aspect.aroundLeader(pjp) }
        wrapped.cause shouldBeEqualTo backendEx
        wrapped.message!! shouldContain "static-group-job"
        wrapped.message!!.contains("redis-prod-01") shouldBeEqualTo false
    }

    @Test
    fun `Fix-94 - factory create 실패 SKIP - null 반환으로 흡수`() {
        class SampleSkipOnCreate {
            @LeaderGroupElection(
                name = "create-fail-group-job",
                maxLeaders = 2,
                failureMode = LeaderAspectFailureMode.SKIP,
            )
            fun run(): String? = SAMPLE_RESULT
        }

        val skipTarget = SampleSkipOnCreate()
        val skipMethod = SampleSkipOnCreate::class.java.getDeclaredMethod("run")
        configureJoinPoint(skipMethod, skipTarget, emptyArray())

        val aspect = newAspect()
        every { factoryMock.create(any()) } throws RuntimeException("factory backend init error")

        aspect.aroundLeader(pjp).shouldBeNull()
    }
}
