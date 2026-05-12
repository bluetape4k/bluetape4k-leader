package io.bluetape4k.leader.spring.aop

import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import io.bluetape4k.leader.annotation.LeaderElection
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.leader.spring.aop.util.LockNameValidator
import io.bluetape4k.logging.KLogging
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.assertFailsWith
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.reflect.MethodSignature
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import reactor.core.publisher.Mono
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [LeaderElectionAspect] — suspend / Mono 분기 커버리지 테스트.
 *
 * ## CTW 제약
 * Freefair CTW 는 main sourceSet 에만 적용. [LeaderElectionAspect.aroundLeader] 를 직접 호출.
 *
 * ## suspend 분기 테스트 방법
 * `suspendCancellableCoroutine { cont -> ... }` 를 사용하여 runTest 컨텍스트를 가진
 * 진짜 `Continuation` 을 얻고 `pjp.args.last()` 로 주입.
 *
 * ## Mono 분기 테스트 방법
 * `aspect.aroundLeader(pjp)` 가 반환하는 `Mono<*>` 를 `.block()` 으로 구독.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderElectionAspectSuspendMonoTest {

    companion object : KLogging() {
        private const val SAMPLE_RESULT = "suspend-ok"
    }

    // ── Suspend 서비스 정의 ──────────────────────────────────────────────────

    private interface SuspendService {
        suspend fun runSuspend(): String?
        suspend fun runSuspendFailOpen(): String?
        suspend fun runSuspendSkip(): String?
    }

    private class SuspendServiceImpl : SuspendService {
        @LeaderElection(name = "suspend-job")
        override suspend fun runSuspend(): String? = SAMPLE_RESULT

        @LeaderElection(name = "suspend-fail-open", failureMode = LeaderAspectFailureMode.FAIL_OPEN_RUN)
        override suspend fun runSuspendFailOpen(): String? = SAMPLE_RESULT

        @LeaderElection(name = "suspend-skip", failureMode = LeaderAspectFailureMode.SKIP)
        override suspend fun runSuspendSkip(): String? = SAMPLE_RESULT
    }

    // ── Mono 서비스 정의 ────────────────────────────────────────────────────

    private interface MonoService {
        fun runMono(): Mono<String>
        fun runMonoFailOpen(): Mono<String>
    }

    private class MonoServiceImpl : MonoService {
        @LeaderElection(name = "mono-job")
        override fun runMono(): Mono<String> = Mono.just(SAMPLE_RESULT)

        @LeaderElection(name = "mono-fail-open", failureMode = LeaderAspectFailureMode.FAIL_OPEN_RUN)
        override fun runMonoFailOpen(): Mono<String> = Mono.just(SAMPLE_RESULT)
    }

    // ── Fake 구현체 ─────────────────────────────────────────────────────────

    /** 항상 선출 성공 — body 직접 실행 */
    private class ElectedSuspendElector : SuspendLeaderElector {
        override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? = action()
    }

    /** 항상 미선출 — null 반환 */
    private class SkippedSuspendElector : SuspendLeaderElector {
        override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? = null
    }

    /** 백엔드 오류 throw */
    private class BackendErrorSuspendElector(private val error: Exception) : SuspendLeaderElector {
        override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? = throw error
    }

    private fun fakeSuspendFactory(elector: SuspendLeaderElector): SuspendLeaderElectorFactory =
        SuspendLeaderElectorFactory { _ -> elector }

    // ── MockK mocks ─────────────────────────────────────────────────────────

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

    private fun newAspect(suspendFactory: SuspendLeaderElectorFactory): LeaderElectionAspect {
        every { beanSelector.selectSuspendElectorFactory(any(), any()) } returns
            LeaderBeanSelector.Selected("testSuspendFactory", suspendFactory)
        return LeaderElectionAspect(
            beanSelector = beanSelector,
            props = LeaderAopProperties(),
            spel = SpelExpressionEvaluator(embeddedValueResolver = { it }, allowMethodInvocation = false),
            lockNameValidator = LockNameValidator(prefix = ""),
            recorders = emptyList(),
        )
    }

    private fun configureSuspendPjp(methodName: String, target: Any) {
        val method = SuspendService::class.java.getDeclaredMethod(methodName, Continuation::class.java)
        every { signature.method } returns method
        every { pjp.signature } returns signature
        every { pjp.target } returns target
    }

    private fun configureMonoJoinPoint(methodName: String, target: Any) {
        val method = MonoService::class.java.getDeclaredMethod(methodName)
        every { signature.method } returns method
        every { pjp.signature } returns signature
        every { pjp.target } returns target
        every { pjp.args } returns emptyArray()
    }

    /**
     * suspend 분기를 runTest 내부에서 호출하는 헬퍼.
     *
     * `suspendCancellableCoroutine` 으로 runTest 컨텍스트의 진짜 Continuation 을 획득하여
     * `pjp.args.last()` 로 주입 후 `aspect.aroundLeader(pjp)` 를 실행.
     */
    private suspend fun runSuspendAspect(aspect: LeaderElectionAspect): Any? =
        suspendCancellableCoroutine { cont ->
            every { pjp.args } returns arrayOf<Any?>(cont)
            val r = aspect.aroundLeader(pjp)
            @Suppress("SuspiciousEqualsCombination")
            if (r !== COROUTINE_SUSPENDED) {
                cont.resume(r)
            }
        }

    // ═══════════════════════════════════════════════════════════════════════
    // Suspend 분기 테스트
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `suspend 선출 성공 - 본문 결과 반환`() = runTest {
        configureSuspendPjp("runSuspend", SuspendServiceImpl())
        every { pjp.proceed(any<Array<Any?>>()) } returns SAMPLE_RESULT

        val aspect = newAspect(fakeSuspendFactory(ElectedSuspendElector()))
        val result = runSuspendAspect(aspect)

        result shouldBeEqualTo SAMPLE_RESULT
    }

    @Test
    fun `suspend 미선출 - null 반환`() = runTest {
        configureSuspendPjp("runSuspend", SuspendServiceImpl())

        val aspect = newAspect(fakeSuspendFactory(SkippedSuspendElector()))
        val result = runSuspendAspect(aspect)

        result.shouldBeNull()
    }

    @Test
    fun `suspend 본문 throw - 예외 전파`() = runTest {
        configureSuspendPjp("runSuspend", SuspendServiceImpl())
        val bodyEx = RuntimeException("body error")
        every { pjp.proceed(any<Array<Any?>>()) } throws bodyEx

        val aspect = newAspect(fakeSuspendFactory(ElectedSuspendElector()))

        val ex = assertFailsWith<RuntimeException> { runSuspendAspect(aspect) }
        ex shouldBeEqualTo bodyEx
    }

    @Test
    fun `suspend 백엔드 throw + RETHROW - LeaderElectionException wrapping`() = runTest {
        configureSuspendPjp("runSuspend", SuspendServiceImpl())
        val backendEx = RuntimeException("redis timeout")

        val aspect = newAspect(fakeSuspendFactory(BackendErrorSuspendElector(backendEx)))

        val ex = assertFailsWith<LeaderElectionException> { runSuspendAspect(aspect) }
        ex.cause shouldBeEqualTo backendEx
    }

    @Test
    fun `suspend 백엔드 throw + SKIP - null 반환`() = runTest {
        configureSuspendPjp("runSuspendSkip", SuspendServiceImpl())
        val backendEx = RuntimeException("redis timeout")

        val aspect = newAspect(fakeSuspendFactory(BackendErrorSuspendElector(backendEx)))
        val result = runSuspendAspect(aspect)

        result.shouldBeNull()
    }

    @Test
    fun `suspend FAIL_OPEN_RUN + 경쟁 - 본문 실행 후 결과 반환`() = runTest {
        configureSuspendPjp("runSuspendFailOpen", SuspendServiceImpl())
        every { pjp.proceed(any<Array<Any?>>()) } returns SAMPLE_RESULT

        val aspect = newAspect(fakeSuspendFactory(SkippedSuspendElector()))
        val result = runSuspendAspect(aspect)

        result shouldBeEqualTo SAMPLE_RESULT
    }

    @Test
    fun `suspend FAIL_OPEN_RUN + 백엔드 오류 - 본문 실행 후 결과 반환`() = runTest {
        configureSuspendPjp("runSuspendFailOpen", SuspendServiceImpl())
        val backendEx = RuntimeException("backend down")
        every { pjp.proceed(any<Array<Any?>>()) } returns SAMPLE_RESULT

        val aspect = newAspect(fakeSuspendFactory(BackendErrorSuspendElector(backendEx)))
        val result = runSuspendAspect(aspect)

        result shouldBeEqualTo SAMPLE_RESULT
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Mono 분기 테스트
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `mono 선출 성공 - 본문 Mono 결과 반환`() {
        configureMonoJoinPoint("runMono", MonoServiceImpl())
        every { pjp.proceed() } returns Mono.just(SAMPLE_RESULT)

        val aspect = newAspect(fakeSuspendFactory(ElectedSuspendElector()))
        val result = (aspect.aroundLeader(pjp) as Mono<*>).block()

        result shouldBeEqualTo SAMPLE_RESULT
    }

    @Test
    fun `mono 미선출 - null 반환`() {
        configureMonoJoinPoint("runMono", MonoServiceImpl())

        val aspect = newAspect(fakeSuspendFactory(SkippedSuspendElector()))
        val result = (aspect.aroundLeader(pjp) as Mono<*>).block()

        result.shouldBeNull()
    }

    @Test
    fun `mono 본문 throw - 예외 전파`() {
        configureMonoJoinPoint("runMono", MonoServiceImpl())
        val bodyEx = RuntimeException("mono body error")
        every { pjp.proceed() } throws bodyEx

        val aspect = newAspect(fakeSuspendFactory(ElectedSuspendElector()))
        val mono = aspect.aroundLeader(pjp) as Mono<*>

        val thrown = assertFailsWith<RuntimeException> { mono.block() }
        thrown shouldBeEqualTo bodyEx
    }

    @Test
    fun `mono 백엔드 throw + RETHROW - LeaderElectionException wrapping`() {
        configureMonoJoinPoint("runMono", MonoServiceImpl())
        val backendEx = RuntimeException("redis down")

        val aspect = newAspect(fakeSuspendFactory(BackendErrorSuspendElector(backendEx)))
        val mono = aspect.aroundLeader(pjp) as Mono<*>

        val ex = assertFailsWith<LeaderElectionException> { mono.block() }
        ex.cause shouldBeEqualTo backendEx
    }

    @Test
    fun `mono 백엔드 throw + SKIP - null 반환`() {
        val skipTarget = object : MonoService {
            @LeaderElection(name = "mono-skip-job", failureMode = LeaderAspectFailureMode.SKIP)
            override fun runMono(): Mono<String> = Mono.just(SAMPLE_RESULT)
            @LeaderElection(name = "mono-fail-open-job2", failureMode = LeaderAspectFailureMode.FAIL_OPEN_RUN)
            override fun runMonoFailOpen(): Mono<String> = Mono.just(SAMPLE_RESULT)
        }
        val method = MonoService::class.java.getDeclaredMethod("runMono")
        every { signature.method } returns method
        every { pjp.signature } returns signature
        every { pjp.target } returns skipTarget
        every { pjp.args } returns emptyArray()

        val backendEx = RuntimeException("redis down")
        val aspect = newAspect(fakeSuspendFactory(BackendErrorSuspendElector(backendEx)))
        val result = (aspect.aroundLeader(pjp) as Mono<*>).block()

        result.shouldBeNull()
    }

    @Test
    fun `mono FAIL_OPEN_RUN + 경쟁 - 본문 Mono 실행 후 결과 반환`() {
        configureMonoJoinPoint("runMonoFailOpen", MonoServiceImpl())
        every { pjp.proceed() } returns Mono.just(SAMPLE_RESULT)

        val aspect = newAspect(fakeSuspendFactory(SkippedSuspendElector()))
        val result = (aspect.aroundLeader(pjp) as Mono<*>).block()

        result shouldBeEqualTo SAMPLE_RESULT
    }

    @Test
    fun `mono FAIL_OPEN_RUN + 백엔드 오류 - 본문 Mono 실행 후 결과 반환`() {
        configureMonoJoinPoint("runMonoFailOpen", MonoServiceImpl())
        every { pjp.proceed() } returns Mono.just(SAMPLE_RESULT)
        val backendEx = RuntimeException("backend down")

        val aspect = newAspect(fakeSuspendFactory(BackendErrorSuspendElector(backendEx)))
        val result = (aspect.aroundLeader(pjp) as Mono<*>).block()

        result shouldBeEqualTo SAMPLE_RESULT
    }

    @Test
    fun `mono 결과가 Mono 타입 검증`() {
        configureMonoJoinPoint("runMono", MonoServiceImpl())
        every { pjp.proceed() } returns Mono.just(SAMPLE_RESULT)

        val aspect = newAspect(fakeSuspendFactory(ElectedSuspendElector()))
        val raw = aspect.aroundLeader(pjp)

        raw.shouldBeInstanceOf<Mono<*>>()
    }
}
