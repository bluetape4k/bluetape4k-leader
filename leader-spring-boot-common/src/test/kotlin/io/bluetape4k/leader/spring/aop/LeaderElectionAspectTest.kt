package io.bluetape4k.leader.spring.aop

import io.bluetape4k.leader.LeaderElection as CoreLeaderElection
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderElectionFactory
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.spring.aop.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.spring.aop.metrics.SkipReason
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.leader.spring.aop.util.LockNameValidator
import io.bluetape4k.logging.KLogging
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldBeNull
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.reflect.MethodSignature
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.BeanFactory
import java.util.concurrent.CancellationException

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
class LeaderElectionAspectTest {

    companion object: KLogging() {
        private const val SAMPLE_RESULT = "ok"
    }

    private interface SampleService {
        fun runSync(): String?
        fun runWithArg(region: String): String?
    }

    private class SampleServiceImpl : SampleService {
        @LeaderElection(name = "static-job")
        override fun runSync(): String? = SAMPLE_RESULT

        @LeaderElection(name = "'r-' + #region")
        override fun runWithArg(region: String): String? = "result-$region"
    }

    private fun newAspect(
        electionMock: CoreLeaderElection,
        recorders: List<LeaderAopMetricsRecorder> = emptyList(),
    ): LeaderElectionAspect {
        val factoryMock: LeaderElectionFactory = mockk()
        every { factoryMock.create(any()) } returns electionMock

        val beanFactory: BeanFactory = mockk()
        val beanSelector: LeaderBeanSelector = mockk()
        every { beanSelector.selectElectionFactory(any()) } returns
            LeaderBeanSelector.Selected("testFactory", factoryMock)

        val spel = SpelExpressionEvaluator(embeddedValueResolver = { it }, allowMethodInvocation = false)
        val props = LeaderAopProperties()
        val validator = LockNameValidator(prefix = "")

        return LeaderElectionAspect(
            beanSelector = beanSelector,
            props = props,
            spel = spel,
            lockNameValidator = validator,
            recorders = recorders,
        )
    }

    private fun newJoinPoint(method: java.lang.reflect.Method, target: Any, args: Array<Any?>): ProceedingJoinPoint {
        val signature: MethodSignature = mockk()
        every { signature.method } returns method

        val pjp: ProceedingJoinPoint = mockk()
        every { pjp.signature } returns signature
        every { pjp.target } returns target
        every { pjp.args } returns args
        return pjp
    }

    @Test
    fun `elected - 본문 결과 반환 + onTaskFinished 호출`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")
        val pjp = newJoinPoint(method, target, emptyArray())
        every { pjp.proceed() } returns SAMPLE_RESULT

        val election: CoreLeaderElection = mockk()
        val actionSlot = slot<() -> Any?>()
        every { election.runIfLeader<Any?>(any(), capture(actionSlot)) } answers { actionSlot.captured.invoke() }

        val recorder = mockk<LeaderAopMetricsRecorder>(relaxed = true)
        val aspect = newAspect(election, listOf(recorder))

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
        val pjp = newJoinPoint(method, target, emptyArray())

        val election: CoreLeaderElection = mockk()
        every { election.runIfLeader<Any?>(any(), any()) } returns null

        val recorder = mockk<LeaderAopMetricsRecorder>(relaxed = true)
        val aspect = newAspect(election, listOf(recorder))

        aspect.aroundLeader(pjp).shouldBeNull()
        verify(exactly = 1) { recorder.onLockNotAcquired("static-job", any(), SkipReason.CONTENTION) }
    }

    @Test
    fun `본문 throw - wrapping 없이 그대로 전파 (Rel-1)`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")
        val pjp = newJoinPoint(method, target, emptyArray())
        val bodyEx = RuntimeException("body failure")
        every { pjp.proceed() } throws bodyEx

        val election: CoreLeaderElection = mockk()
        val actionSlot = slot<() -> Any?>()
        every { election.runIfLeader<Any?>(any(), capture(actionSlot)) } answers { actionSlot.captured.invoke() }

        val aspect = newAspect(election)
        val ex = assertThrows<RuntimeException> { aspect.aroundLeader(pjp) }
        ex shouldBeEqualTo bodyEx  // wrapping 없음
    }

    @Test
    fun `backend throw - LeaderElectionException wrapping (Sec-3 R-33)`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")
        val pjp = newJoinPoint(method, target, emptyArray())

        val election: CoreLeaderElection = mockk()
        val backendEx = RuntimeException("Connection to redis-prod-01.internal:6379 timeout")
        every { election.runIfLeader<Any?>(any(), any()) } throws backendEx

        val aspect = newAspect(election)
        val wrapped = assertThrows<LeaderElectionException> { aspect.aroundLeader(pjp) }
        wrapped.cause shouldBeEqualTo backendEx
        // message 일반화 — host 정보 미포함
        wrapped.message!!.contains("redis-prod-01") shouldBeEqualTo false
        wrapped.message!!.contains("static-job") shouldBeEqualTo true
    }

    @Test
    fun `CancellationException - 우선 재throw (Rel-2)`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")
        val pjp = newJoinPoint(method, target, emptyArray())
        val cancelEx = CancellationException("cancelled")
        every { pjp.proceed() } throws cancelEx

        val election: CoreLeaderElection = mockk()
        val actionSlot = slot<() -> Any?>()
        every { election.runIfLeader<Any?>(any(), capture(actionSlot)) } answers { actionSlot.captured.invoke() }

        val aspect = newAspect(election)
        val ex = assertThrows<CancellationException> { aspect.aroundLeader(pjp) }
        ex shouldBeEqualTo cancelEx  // wrapping 없음
    }

    @Test
    fun `SKIP 모드 - backend throw 흡수 후 null`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")

        // SKIP 모드를 위한 별도 sample
        class SampleSkip {
            @LeaderElection(name = "skip-job", failureMode = LeaderAspectFailureMode.SKIP)
            fun run(): String? = SAMPLE_RESULT
        }
        val skipTarget = SampleSkip()
        val skipMethod = SampleSkip::class.java.getDeclaredMethod("run")
        val pjp = newJoinPoint(skipMethod, skipTarget, emptyArray())

        val election: CoreLeaderElection = mockk()
        val backendEx = RuntimeException("backend down")
        every { election.runIfLeader<Any?>(any(), any()) } throws backendEx

        val aspect = newAspect(election)
        aspect.aroundLeader(pjp).shouldBeNull()
    }

    @Test
    fun `metrics fast-path - recorders 0개 시 fanOut 진입 없음 (Perf-1)`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")
        val pjp = newJoinPoint(method, target, emptyArray())
        every { pjp.proceed() } returns SAMPLE_RESULT

        val election: CoreLeaderElection = mockk()
        val actionSlot = slot<() -> Any?>()
        every { election.runIfLeader<Any?>(any(), capture(actionSlot)) } answers { actionSlot.captured.invoke() }

        val aspect = newAspect(election, recorders = emptyList())
        // recorders 0개 — fanOut 호출 없이 정상 동작
        aspect.aroundLeader(pjp) shouldBeEqualTo SAMPLE_RESULT
    }

    @Test
    fun `metrics isolation - 한 recorder throw 시 다른 recorder 영향 없음`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")
        val pjp = newJoinPoint(method, target, emptyArray())
        every { pjp.proceed() } returns SAMPLE_RESULT

        val election: CoreLeaderElection = mockk()
        val actionSlot = slot<() -> Any?>()
        every { election.runIfLeader<Any?>(any(), capture(actionSlot)) } answers { actionSlot.captured.invoke() }

        val throwingRecorder = mockk<LeaderAopMetricsRecorder>(relaxed = true)
        every { throwingRecorder.onLockAttempt(any(), any()) } throws RuntimeException("recorder failure")

        val healthyRecorder = mockk<LeaderAopMetricsRecorder>(relaxed = true)

        val aspect = newAspect(election, listOf(throwingRecorder, healthyRecorder))
        aspect.aroundLeader(pjp) shouldBeEqualTo SAMPLE_RESULT  // body 정상 실행

        // healthy recorder 는 모든 콜백 호출됨
        verify { healthyRecorder.onLockAttempt("static-job", any()) }
        verify { healthyRecorder.onTaskFinished("static-job", any()) }
    }

    @Test
    fun `Method-level cache - annotation lookup 호출당 1회만 (Perf-2)`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")

        val election: CoreLeaderElection = mockk()
        val actionSlot = slot<() -> Any?>()
        every { election.runIfLeader<Any?>(any(), capture(actionSlot)) } answers { actionSlot.captured.invoke() }

        val factoryMock: LeaderElectionFactory = mockk()
        every { factoryMock.create(any()) } returns election

        val beanSelector: LeaderBeanSelector = mockk()
        every { beanSelector.selectElectionFactory(any()) } returns
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
            val pjp = newJoinPoint(method, target, emptyArray())
            every { pjp.proceed() } returns SAMPLE_RESULT
            aspect.aroundLeader(pjp)
        }

        // beanSelector.selectElectionFactory 는 100번이 아니라 1번만 호출 (cache hit)
        verify(exactly = 1) { beanSelector.selectElectionFactory(any()) }
    }

    @Test
    fun `SpEL 평가 - lock name 동적 해석`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runWithArg", String::class.java)
        val pjp = newJoinPoint(method, target, arrayOf("EU"))
        every { pjp.proceed() } returns "result-EU"

        val election: CoreLeaderElection = mockk()
        val actionSlot = slot<() -> Any?>()
        val nameSlot = slot<String>()
        every { election.runIfLeader<Any?>(capture(nameSlot), capture(actionSlot)) } answers { actionSlot.captured.invoke() }

        val aspect = newAspect(election)
        val result = aspect.aroundLeader(pjp)

        nameSlot.captured shouldBeEqualTo "r-EU"
        result shouldBeEqualTo "result-EU"
    }

    @Test
    fun `wrapped exception 은 LeaderElectionException 인스턴스`() {
        val target = SampleServiceImpl()
        val method = SampleService::class.java.getDeclaredMethod("runSync")
        val pjp = newJoinPoint(method, target, emptyArray())

        val election: CoreLeaderElection = mockk()
        every { election.runIfLeader<Any?>(any(), any()) } throws IllegalStateException("backend")

        val aspect = newAspect(election)
        val ex = assertThrows<LeaderElectionException> { aspect.aroundLeader(pjp) }
        ex shouldBeInstanceOf LeaderElectionException::class
    }
}
