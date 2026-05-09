package io.bluetape4k.leader.spring.aop

import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import io.bluetape4k.leader.annotation.LeaderElection
import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.metrics.SkipReason
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.leader.spring.aop.util.LockNameValidator
import io.bluetape4k.leader.spring.aop.validator.ComposedLeaderElection
import io.bluetape4k.logging.KLogging
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.reflect.MethodSignature
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import io.bluetape4k.assertions.assertFailsWith
import java.util.concurrent.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * [LeaderElectionAspect] 통합 (T5.6 + T5.9c/d/e/f):
 * - 정상 elected → 본문 결과 반환 + onTaskFinished
 * - 미선출 → null 반환 + onLockNotAcquired(CONTENTION)
 * - 본문 throw → wrapping 없이 그대로 전파 [Step 3-P-Rel-1]
 * - backend throw → LeaderElectionException wrapping [Step 3-P-Sec-3][R-33]
 * - CancellationException → 우선 재throw [Step 3-P-Rel-2]
 * - SKIP 모드 → backend throw 흡수 후 null
 * - metrics fast-path — recorder 0개 시 fanOut 진입 없음 [Step 3-P-Perf-1]
 * - Method-level cache — annotation lookup 호출당 1회만 [Step 3-P-Perf-2]
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderElectionAspectTest {

    companion object: KLogging() {
        private const val SAMPLE_RESULT = "ok"
    }

    private interface SampleService {
        fun runSync(): String?
        fun runWithArg(region: String): String?
        fun runWithMinLease(): String?
    }

    private class SampleServiceImpl: SampleService {
        @LeaderElection(name = "static-job")
        override fun runSync(): String? = SAMPLE_RESULT

        @LeaderElection(name = "'r-' + #region")
        override fun runWithArg(region: String): String? = "result-$region"

        @LeaderElection(name = "min-job", leaseTime = "PT30S", minLeaseTime = "PT10S")
        override fun runWithMinLease(): String? = SAMPLE_RESULT
    }

    // Class-level mocks — reused across all tests, cleared in @BeforeEach
    private val election: LeaderElector = mockk(relaxed = true)
    private val recorder: LeaderAopMetricsRecorder = mockk(relaxed = true)
    private val signature: MethodSignature = mockk()
    private val pjp: ProceedingJoinPoint = mockk()
    private val factoryMock: LeaderElectorFactory = mockk()
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

    @Test
    fun `elected - 본문 결과 반환 + onTaskFinished 호출`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")
        configureJoinPoint(method, target, emptyArray())
        every { pjp.proceed() } returns SAMPLE_RESULT

        val actionSlot = slot<() -> Any?>()
        every { election.runIfLeaderResult<Any?>(any(), capture(actionSlot)) } answers { LeaderRunResult.Elected(actionSlot.captured.invoke()) }

        val aspect = newAspect(listOf(recorder))

        val result = aspect.aroundLeader(pjp)
        result shouldBeEqualTo SAMPLE_RESULT

        verify(exactly = 1) { recorder.onLockAttempt("static-job", any()) }
        verify(exactly = 1) { recorder.onLockAcquired("static-job", any(), any()) }
        verify(exactly = 1) { recorder.onTaskStarted("static-job") }
        verify(exactly = 1) { recorder.onTaskFinished("static-job", any()) }
    }

    @Test
    fun `미선출 - null 반환 + onLockNotAcquired(CONTENTION)`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")
        configureJoinPoint(method, target, emptyArray())

        every { election.runIfLeaderResult<Any?>(any(), any()) } returns LeaderRunResult.Skipped

        val aspect = newAspect(listOf(recorder))

        aspect.aroundLeader(pjp).shouldBeNull()
        verify(exactly = 1) { recorder.onLockNotAcquired("static-job", any(), SkipReason.CONTENTION) }
    }

    @Test
    fun `minLeaseTime - 어노테이션 값을 core options 로 전달한다`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runWithMinLease")
        configureJoinPoint(method, target, emptyArray())
        every { pjp.proceed() } returns SAMPLE_RESULT

        val actionSlot = slot<() -> Any?>()
        every { election.runIfLeaderResult<Any?>(any(), capture(actionSlot)) } answers {
            LeaderRunResult.Elected(actionSlot.captured.invoke())
        }
        val optionsSlot = slot<LeaderElectionOptions>()

        val aspect = newAspect()
        every { factoryMock.create(capture(optionsSlot)) } returns election

        val result = aspect.aroundLeader(pjp)

        result shouldBeEqualTo SAMPLE_RESULT
        optionsSlot.captured.leaseTime shouldBeEqualTo 30.seconds
        optionsSlot.captured.minLeaseTime shouldBeEqualTo 10.seconds
    }

    @Test
    fun `본문 throw - wrapping 없이 그대로 전파 (Rel-1)`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")
        configureJoinPoint(method, target, emptyArray())
        val bodyEx = RuntimeException("body failure")
        every { pjp.proceed() } throws bodyEx

        val actionSlot = slot<() -> Any?>()
        every { election.runIfLeaderResult<Any?>(any(), capture(actionSlot)) } answers { LeaderRunResult.Elected(actionSlot.captured.invoke()) }

        val aspect = newAspect()
        val ex = assertFailsWith<RuntimeException> { aspect.aroundLeader(pjp) }
        ex shouldBeEqualTo bodyEx  // wrapping 없음
    }

    @Test
    fun `backend throw - LeaderElectionException wrapping (Sec-3 R-33)`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")
        configureJoinPoint(method, target, emptyArray())

        val backendEx = RuntimeException("Connection to redis-prod-01.internal:6379 timeout")
        every { election.runIfLeaderResult<Any?>(any(), any()) } throws backendEx

        val aspect = newAspect()
        val wrapped = assertFailsWith<LeaderElectionException> { aspect.aroundLeader(pjp) }
        wrapped.cause shouldBeEqualTo backendEx
        // message 일반화 — host 정보 미포함
        wrapped.message!!.contains("redis-prod-01") shouldBeEqualTo false
        wrapped.message!!.contains("static-job") shouldBeEqualTo true
    }

    @Test
    fun `CancellationException - 우선 재throw (Rel-2)`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")
        configureJoinPoint(method, target, emptyArray())
        val cancelEx = CancellationException("cancelled")
        every { pjp.proceed() } throws cancelEx

        val actionSlot = slot<() -> Any?>()
        every { election.runIfLeaderResult<Any?>(any(), capture(actionSlot)) } answers { LeaderRunResult.Elected(actionSlot.captured.invoke()) }

        val aspect = newAspect()
        val ex = assertFailsWith<CancellationException> { aspect.aroundLeader(pjp) }
        ex shouldBeEqualTo cancelEx  // wrapping 없음
    }

    @Test
    fun `SKIP 모드 - backend throw 흡수 후 null`() {
        class SampleSkip {
            @LeaderElection(name = "skip-job", failureMode = LeaderAspectFailureMode.SKIP)
            fun run(): String? = SAMPLE_RESULT
        }

        val skipTarget = SampleSkip()
        val skipMethod = SampleSkip::class.java.getDeclaredMethod("run")
        configureJoinPoint(skipMethod, skipTarget, emptyArray())

        val backendEx = RuntimeException("backend down")
        every { election.runIfLeaderResult<Any?>(any(), any()) } throws backendEx

        val aspect = newAspect()
        aspect.aroundLeader(pjp).shouldBeNull()
    }

    @Test
    fun `metrics fast-path - recorders 0개 시 fanOut 진입 없음 (Perf-1)`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")
        configureJoinPoint(method, target, emptyArray())
        every { pjp.proceed() } returns SAMPLE_RESULT

        val actionSlot = slot<() -> Any?>()
        every { election.runIfLeaderResult<Any?>(any(), capture(actionSlot)) } answers { LeaderRunResult.Elected(actionSlot.captured.invoke()) }

        val aspect = newAspect(recorders = emptyList())
        // recorders 0개 — fanOut 호출 없이 정상 동작
        aspect.aroundLeader(pjp) shouldBeEqualTo SAMPLE_RESULT
    }

    @Test
    fun `metrics isolation - 한 recorder throw 시 다른 recorder 영향 없음`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")
        configureJoinPoint(method, target, emptyArray())
        every { pjp.proceed() } returns SAMPLE_RESULT

        val actionSlot = slot<() -> Any?>()
        every { election.runIfLeaderResult<Any?>(any(), capture(actionSlot)) } answers { LeaderRunResult.Elected(actionSlot.captured.invoke()) }

        val throwingRecorder = mockk<LeaderAopMetricsRecorder>(relaxed = true)
        every { throwingRecorder.onLockAttempt(any(), any()) } throws RuntimeException("recorder failure")

        // recorder (class-level, relaxed) = healthy recorder
        val aspect = newAspect(listOf(throwingRecorder, recorder))
        aspect.aroundLeader(pjp) shouldBeEqualTo SAMPLE_RESULT  // body 정상 실행

        // healthy recorder 는 모든 콜백 호출됨
        verify { recorder.onLockAttempt("static-job", any()) }
        verify { recorder.onTaskFinished("static-job", any()) }
    }

    @Test
    fun `Method-level cache - annotation lookup 호출당 1회만 (Perf-2)`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")

        val actionSlot = slot<() -> Any?>()
        every { election.runIfLeaderResult<Any?>(any(), capture(actionSlot)) } answers { LeaderRunResult.Elected(actionSlot.captured.invoke()) }
        every { factoryMock.create(any()) } returns election
        every { beanSelector.selectElectionFactory(any(), any()) } returns
                LeaderBeanSelector.Selected("testFactory", factoryMock)

        val aspect = LeaderElectionAspect(
            beanSelector = beanSelector,
            props = LeaderAopProperties(),
            spel = SpelExpressionEvaluator(embeddedValueResolver = { it }),
            lockNameValidator = LockNameValidator(),
            recorders = emptyList(),
        )

        // 동일 메서드 100번 호출
        repeat(100) {
            configureJoinPoint(method, target, emptyArray())
            every { pjp.proceed() } returns SAMPLE_RESULT
            aspect.aroundLeader(pjp)
        }

        // beanSelector.selectElectionFactory 는 100번이 아니라 1번만 호출 (cache hit)
        verify(exactly = 1) { beanSelector.selectElectionFactory(any(), any()) }
    }

    @Test
    fun `SpEL 평가 - lock name 동적 해석`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runWithArg", String::class.java)
        configureJoinPoint(method, target, arrayOf("EU"))
        every { pjp.proceed() } returns "result-EU"

        val actionSlot = slot<() -> Any?>()
        val nameSlot = slot<String>()
        every {
            election.runIfLeaderResult<Any?>(
                capture(nameSlot),
                capture(actionSlot)
            )
        } answers { LeaderRunResult.Elected(actionSlot.captured.invoke()) }

        val aspect = newAspect()
        val result = aspect.aroundLeader(pjp)

        nameSlot.captured shouldBeEqualTo "r-EU"
        result shouldBeEqualTo "result-EU"
    }

    @Test
    fun `wrapped exception 은 LeaderElectionException 인스턴스`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")
        configureJoinPoint(method, target, emptyArray())

        every { election.runIfLeaderResult<Any?>(any(), any()) } throws IllegalStateException("backend")

        val aspect = newAspect()
        val ex = assertFailsWith<LeaderElectionException> { aspect.aroundLeader(pjp) }
        ex shouldBeInstanceOf LeaderElectionException::class
    }

    // ── Fix-94: factory.create() I/O 실패가 failureMode 우회하던 버그 회귀 테스트 ──

    @Test
    fun `Fix-94 - factory create 실패 RETHROW - LeaderElectionException wrapping + lockName 포함 + host 누출 없음`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")
        configureJoinPoint(method, target, emptyArray())

        val backendEx = RuntimeException("Connection to redis-prod-01.internal:6379 refused")
        val aspect = newAspect()
        // factory.create 가 backend I/O 실패로 throw — try 블록 안으로 이동되어야 failureMode 가 적용됨
        every { factoryMock.create(any()) } throws backendEx

        val wrapped = assertFailsWith<LeaderElectionException> { aspect.aroundLeader(pjp) }
        wrapped.cause shouldBeEqualTo backendEx
        // lockName 은 포함, backend host 정보는 누출 안 됨 (R-33)
        wrapped.message!!.contains("static-job") shouldBeEqualTo true
        wrapped.message!!.contains("redis-prod-01") shouldBeEqualTo false
    }

    @Test
    fun `Fix-94 - factory create 실패 SKIP - null 반환으로 흡수`() {
        class SampleSkipOnCreate {
            @LeaderElection(name = "create-fail-job", failureMode = LeaderAspectFailureMode.SKIP)
            fun run(): String? = SAMPLE_RESULT
        }

        val skipTarget = SampleSkipOnCreate()
        val skipMethod = SampleSkipOnCreate::class.java.getDeclaredMethod("run")
        configureJoinPoint(skipMethod, skipTarget, emptyArray())

        val aspect = newAspect()
        every { factoryMock.create(any()) } throws RuntimeException("factory backend init error")

        // SKIP 모드 → backend I/O 실패를 흡수하고 null 반환
        aspect.aroundLeader(pjp).shouldBeNull()
    }

    // ── FAIL_OPEN_RUN 시나리오 ──

    @Test
    fun `FAIL_OPEN_RUN - 경쟁(Skipped) 시 락 없이 본문 실행 후 결과 반환`() {
        class SampleFailOpen {
            @LeaderElection(name = "fail-open-job", failureMode = LeaderAspectFailureMode.FAIL_OPEN_RUN)
            fun run(): String? = SAMPLE_RESULT
        }

        val target = SampleFailOpen()
        val method = SampleFailOpen::class.java.getDeclaredMethod("run")
        configureJoinPoint(method, target, emptyArray())
        every { pjp.proceed() } returns SAMPLE_RESULT
        every { election.runIfLeaderResult<Any?>(any(), any()) } returns LeaderRunResult.Skipped

        val aspect = newAspect(listOf(recorder))
        val result = aspect.aroundLeader(pjp)

        result shouldBeEqualTo SAMPLE_RESULT
        verify(exactly = 1) { recorder.onLockNotAcquired("fail-open-job", any(), SkipReason.FAIL_OPEN_FORCED) }
        verify(exactly = 1) { recorder.onTaskStarted("fail-open-job") }
        verify(exactly = 1) { recorder.onTaskFinished("fail-open-job", any()) }
    }

    @Test
    fun `FAIL_OPEN_RUN - 경쟁(Skipped) 시 본문 throw 는 그대로 전파`() {
        class SampleFailOpen {
            @LeaderElection(name = "fail-open-job", failureMode = LeaderAspectFailureMode.FAIL_OPEN_RUN)
            fun run(): String? = SAMPLE_RESULT
        }

        val target = SampleFailOpen()
        val method = SampleFailOpen::class.java.getDeclaredMethod("run")
        configureJoinPoint(method, target, emptyArray())
        val bodyEx = RuntimeException("body failure during fail-open")
        every { pjp.proceed() } throws bodyEx
        every { election.runIfLeaderResult<Any?>(any(), any()) } returns LeaderRunResult.Skipped

        val aspect = newAspect()
        val ex = assertFailsWith<RuntimeException> { aspect.aroundLeader(pjp) }
        ex shouldBeEqualTo bodyEx
    }

    @Test
    fun `FAIL_OPEN_RUN - backend 예외 시 락 없이 본문 실행 후 결과 반환`() {
        class SampleFailOpen {
            @LeaderElection(name = "fail-open-job", failureMode = LeaderAspectFailureMode.FAIL_OPEN_RUN)
            fun run(): String? = SAMPLE_RESULT
        }

        val target = SampleFailOpen()
        val method = SampleFailOpen::class.java.getDeclaredMethod("run")
        configureJoinPoint(method, target, emptyArray())
        every { pjp.proceed() } returns SAMPLE_RESULT
        val backendEx = RuntimeException("redis down")
        every { election.runIfLeaderResult<Any?>(any(), any()) } throws backendEx

        val aspect = newAspect(listOf(recorder))
        val result = aspect.aroundLeader(pjp)

        result shouldBeEqualTo SAMPLE_RESULT
        verify(exactly = 1) { recorder.onLockNotAcquired("fail-open-job", any(), SkipReason.FAIL_OPEN_FORCED) }
        verify(exactly = 1) { recorder.onTaskStarted("fail-open-job") }
        verify(exactly = 1) { recorder.onTaskFinished("fail-open-job", any()) }
    }

    @Test
    fun `FAIL_OPEN_RUN - backend 예외 후 본문 throw 는 그대로 전파`() {
        class SampleFailOpen {
            @LeaderElection(name = "fail-open-job", failureMode = LeaderAspectFailureMode.FAIL_OPEN_RUN)
            fun run(): String? = SAMPLE_RESULT
        }

        val target = SampleFailOpen()
        val method = SampleFailOpen::class.java.getDeclaredMethod("run")
        configureJoinPoint(method, target, emptyArray())
        val bodyEx = IllegalStateException("body failure after backend error")
        every { pjp.proceed() } throws bodyEx
        every { election.runIfLeaderResult<Any?>(any(), any()) } throws RuntimeException("backend down")

        val aspect = newAspect()
        val ex = assertFailsWith<IllegalStateException> { aspect.aroundLeader(pjp) }
        ex shouldBeEqualTo bodyEx
    }

    // ── #84: 메타 어노테이션(@AliasFor) — Aspect lockName 해석 ──

    @Test
    fun `composed @LeaderElection - Aspect가 merged name 으로 elected 실행`() {
        class SampleComposed {
            @ComposedLeaderElection(name = "composed-alias-job")
            open fun run(): String? = SAMPLE_RESULT
        }

        val target = SampleComposed()
        val method = SampleComposed::class.java.getDeclaredMethod("run")
        configureJoinPoint(method, target, emptyArray())

        val nameSlot = slot<String>()
        every { election.runIfLeaderResult<Any?>(capture(nameSlot), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            (secondArg<() -> Any?>())()
            LeaderRunResult.Elected(SAMPLE_RESULT)
        }
        every { pjp.proceed() } returns SAMPLE_RESULT

        val aspect = newAspect()
        val result = aspect.aroundLeader(pjp)

        result shouldBeEqualTo SAMPLE_RESULT
        nameSlot.captured shouldBeEqualTo "composed-alias-job"
    }
}
