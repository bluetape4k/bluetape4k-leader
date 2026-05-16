package io.bluetape4k.leader.spring.aop

import io.bluetape4k.leader.LeaderGroupElectionException
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import io.bluetape4k.leader.annotation.LeaderGroupElection
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.reflect.MethodSignature
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

/**
 * [LeaderGroupElectionAspect] — suspend / Mono 분기 커버리지 테스트.
 *
 * CTW 는 main sourceSet 에만 적용되므로, [LeaderGroupElectionAspect.aroundLeader] 를 직접 호출하여 검증.
 * suspend 분기는 `suspendCancellableCoroutine` 으로 runTest 컨텍스트의 Continuation 을 획득하여 주입.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderGroupElectionAspectSuspendMonoTest {

    companion object : KLogging() {
        private const val SAMPLE_RESULT = "group-suspend-ok"
    }

    // ── Suspend 서비스 정의 ──────────────────────────────────────────────────

    private interface SuspendGroupService {
        suspend fun runSuspend(): String?
        suspend fun runSuspendFailOpen(): String?
        suspend fun runSuspendSkip(): String?
    }

    private class SuspendGroupServiceImpl : SuspendGroupService {
        @LeaderGroupElection(name = "g-suspend-job", maxLeaders = 3)
        override suspend fun runSuspend(): String? = SAMPLE_RESULT

        @LeaderGroupElection(name = "g-suspend-fail-open", maxLeaders = 3, failureMode = LeaderAspectFailureMode.FAIL_OPEN_RUN)
        override suspend fun runSuspendFailOpen(): String? = SAMPLE_RESULT

        @LeaderGroupElection(name = "g-suspend-skip", maxLeaders = 3, failureMode = LeaderAspectFailureMode.SKIP)
        override suspend fun runSuspendSkip(): String? = SAMPLE_RESULT
    }

    // ── Mono 서비스 정의 ────────────────────────────────────────────────────

    private interface MonoGroupService {
        fun runMono(): Mono<String>
        fun runMonoFailOpen(): Mono<String>
    }

    private class MonoGroupServiceImpl : MonoGroupService {
        @LeaderGroupElection(name = "g-mono-job", maxLeaders = 3)
        override fun runMono(): Mono<String> = Mono.just(SAMPLE_RESULT)

        @LeaderGroupElection(name = "g-mono-fail-open", maxLeaders = 3, failureMode = LeaderAspectFailureMode.FAIL_OPEN_RUN)
        override fun runMonoFailOpen(): Mono<String> = Mono.just(SAMPLE_RESULT)
    }

    private interface StreamGroupService {
        fun runFlux(): Flux<String>
        fun runFlow(): Flow<String>
    }

    private class StreamGroupServiceImpl : StreamGroupService {
        @LeaderGroupElection(name = "g-flux-job", maxLeaders = 3)
        override fun runFlux(): Flux<String> = Flux.just(SAMPLE_RESULT)

        @LeaderGroupElection(name = "g-flow-job", maxLeaders = 3)
        override fun runFlow(): Flow<String> = flowOf(SAMPLE_RESULT)
    }

    // ── Fake 구현체 ─────────────────────────────────────────────────────────

    private abstract class BaseFakeGroupElector : SuspendLeaderGroupElector {
        override val maxLeaders: Int get() = 3
        override fun activeCount(lockName: String): Int = 0
        override fun availableSlots(lockName: String): Int = maxLeaders
        override fun state(lockName: String): LeaderGroupState =
            LeaderGroupState(lockName = lockName, maxLeaders = maxLeaders, activeCount = 0)
    }

    private class ElectedGroupElector : BaseFakeGroupElector() {
        override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? = action()
    }

    private class SkippedGroupElector : BaseFakeGroupElector() {
        override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? = null
    }

    private class BackendErrorGroupElector(private val error: Exception) : BaseFakeGroupElector() {
        override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? = throw error
    }

    private fun fakeGroupFactory(elector: SuspendLeaderGroupElector): SuspendLeaderGroupElectorFactory =
        SuspendLeaderGroupElectorFactory { _ -> elector }

    // ── MockK mocks ─────────────────────────────────────────────────────────

    private val factoryMock: LeaderGroupElectorFactory = mockk()
    private val beanSelector: LeaderBeanSelector = mockk()
    private val signature: MethodSignature = mockk()
    private val pjp: ProceedingJoinPoint = mockk()

    @BeforeEach
    fun setUp() {
        clearMocks(factoryMock, beanSelector, signature, pjp)
        every { beanSelector.selectGroupElectionFactory(any(), any()) } returns
            LeaderBeanSelector.Selected("testGroupFactory", factoryMock)
    }

    private fun newAspect(suspendGroupFactory: SuspendLeaderGroupElectorFactory): LeaderGroupElectionAspect {
        every { beanSelector.selectSuspendGroupElectorFactory(any(), any()) } returns
            LeaderBeanSelector.Selected("testSuspendGroupFactory", suspendGroupFactory)
        return LeaderGroupElectionAspect(
            beanSelector = beanSelector,
            props = LeaderAopProperties(),
            spel = SpelExpressionEvaluator(embeddedValueResolver = { it }, allowMethodInvocation = false),
            lockNameValidator = LockNameValidator(prefix = ""),
            recorders = emptyList(),
        )
    }

    private fun configureSuspendPjp(methodName: String, target: Any) {
        val method = SuspendGroupService::class.java.getDeclaredMethod(methodName, Continuation::class.java)
        every { signature.method } returns method
        every { pjp.signature } returns signature
        every { pjp.target } returns target
    }

    private fun configureMonoJoinPoint(methodName: String, target: Any) {
        val method = MonoGroupService::class.java.getDeclaredMethod(methodName)
        every { signature.method } returns method
        every { pjp.signature } returns signature
        every { pjp.target } returns target
        every { pjp.args } returns emptyArray()
    }

    private fun configureStreamJoinPoint(methodName: String, target: Any) {
        val method = StreamGroupService::class.java.getDeclaredMethod(methodName)
        every { signature.method } returns method
        every { pjp.signature } returns signature
        every { pjp.target } returns target
        every { pjp.args } returns emptyArray()
    }

    private suspend fun runSuspendAspect(aspect: LeaderGroupElectionAspect): Any? =
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
    fun `group suspend 선출 성공 - 본문 결과 반환`() = runTest {
        configureSuspendPjp("runSuspend", SuspendGroupServiceImpl())
        every { pjp.proceed(any<Array<Any?>>()) } returns SAMPLE_RESULT

        val aspect = newAspect(fakeGroupFactory(ElectedGroupElector()))
        val result = runSuspendAspect(aspect)

        result shouldBeEqualTo SAMPLE_RESULT
    }

    @Test
    fun `group suspend 미선출 - null 반환`() = runTest {
        configureSuspendPjp("runSuspend", SuspendGroupServiceImpl())

        val aspect = newAspect(fakeGroupFactory(SkippedGroupElector()))
        val result = runSuspendAspect(aspect)

        result.shouldBeNull()
    }

    @Test
    fun `group suspend 본문 throw - 예외 전파`() = runTest {
        configureSuspendPjp("runSuspend", SuspendGroupServiceImpl())
        val bodyEx = RuntimeException("group body error")
        every { pjp.proceed(any<Array<Any?>>()) } throws bodyEx

        val aspect = newAspect(fakeGroupFactory(ElectedGroupElector()))

        val ex = assertFailsWith<RuntimeException> { runSuspendAspect(aspect) }
        ex shouldBeEqualTo bodyEx
    }

    @Test
    fun `group suspend 백엔드 throw + RETHROW - LeaderGroupElectionException wrapping`() = runTest {
        configureSuspendPjp("runSuspend", SuspendGroupServiceImpl())
        val backendEx = RuntimeException("redis timeout")

        val aspect = newAspect(fakeGroupFactory(BackendErrorGroupElector(backendEx)))

        val ex = assertFailsWith<LeaderGroupElectionException> { runSuspendAspect(aspect) }
        ex.cause shouldBeEqualTo backendEx
    }

    @Test
    fun `group suspend 백엔드 throw + SKIP - null 반환`() = runTest {
        configureSuspendPjp("runSuspendSkip", SuspendGroupServiceImpl())
        val backendEx = RuntimeException("redis timeout")

        val aspect = newAspect(fakeGroupFactory(BackendErrorGroupElector(backendEx)))
        val result = runSuspendAspect(aspect)

        result.shouldBeNull()
    }

    @Test
    fun `group suspend FAIL_OPEN_RUN + 경쟁 - 본문 실행 후 결과 반환`() = runTest {
        configureSuspendPjp("runSuspendFailOpen", SuspendGroupServiceImpl())
        every { pjp.proceed(any<Array<Any?>>()) } returns SAMPLE_RESULT

        val aspect = newAspect(fakeGroupFactory(SkippedGroupElector()))
        val result = runSuspendAspect(aspect)

        result shouldBeEqualTo SAMPLE_RESULT
    }

    @Test
    fun `group suspend FAIL_OPEN_RUN + 백엔드 오류 - 본문 실행 후 결과 반환`() = runTest {
        configureSuspendPjp("runSuspendFailOpen", SuspendGroupServiceImpl())
        val backendEx = RuntimeException("backend down")
        every { pjp.proceed(any<Array<Any?>>()) } returns SAMPLE_RESULT

        val aspect = newAspect(fakeGroupFactory(BackendErrorGroupElector(backendEx)))
        val result = runSuspendAspect(aspect)

        result shouldBeEqualTo SAMPLE_RESULT
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Mono 분기 테스트
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `group mono 선출 성공 - 본문 Mono 결과 반환`() {
        configureMonoJoinPoint("runMono", MonoGroupServiceImpl())
        every { pjp.proceed() } returns Mono.just(SAMPLE_RESULT)

        val aspect = newAspect(fakeGroupFactory(ElectedGroupElector()))
        val result = (aspect.aroundLeader(pjp) as Mono<*>).block()

        result shouldBeEqualTo SAMPLE_RESULT
    }

    @Test
    fun `group mono 미선출 - null 반환`() {
        configureMonoJoinPoint("runMono", MonoGroupServiceImpl())

        val aspect = newAspect(fakeGroupFactory(SkippedGroupElector()))
        val result = (aspect.aroundLeader(pjp) as Mono<*>).block()

        result.shouldBeNull()
    }

    @Test
    fun `group mono 본문 throw - 예외 전파`() {
        configureMonoJoinPoint("runMono", MonoGroupServiceImpl())
        val bodyEx = RuntimeException("group mono body error")
        every { pjp.proceed() } throws bodyEx

        val aspect = newAspect(fakeGroupFactory(ElectedGroupElector()))
        val mono = aspect.aroundLeader(pjp) as Mono<*>

        val thrown = assertFailsWith<RuntimeException> { mono.block() }
        thrown shouldBeEqualTo bodyEx
    }

    @Test
    fun `group mono 백엔드 throw + RETHROW - LeaderGroupElectionException wrapping`() {
        configureMonoJoinPoint("runMono", MonoGroupServiceImpl())
        val backendEx = RuntimeException("redis down")

        val aspect = newAspect(fakeGroupFactory(BackendErrorGroupElector(backendEx)))
        val mono = aspect.aroundLeader(pjp) as Mono<*>

        val ex = assertFailsWith<LeaderGroupElectionException> { mono.block() }
        ex.cause shouldBeEqualTo backendEx
    }

    @Test
    fun `group mono FAIL_OPEN_RUN + 경쟁 - 본문 Mono 실행 후 결과 반환`() {
        configureMonoJoinPoint("runMonoFailOpen", MonoGroupServiceImpl())
        every { pjp.proceed() } returns Mono.just(SAMPLE_RESULT)

        val aspect = newAspect(fakeGroupFactory(SkippedGroupElector()))
        val result = (aspect.aroundLeader(pjp) as Mono<*>).block()

        result shouldBeEqualTo SAMPLE_RESULT
    }

    @Test
    fun `group mono FAIL_OPEN_RUN + 백엔드 오류 - 본문 Mono 실행 후 결과 반환`() {
        configureMonoJoinPoint("runMonoFailOpen", MonoGroupServiceImpl())
        every { pjp.proceed() } returns Mono.just(SAMPLE_RESULT)
        val backendEx = RuntimeException("backend down")

        val aspect = newAspect(fakeGroupFactory(BackendErrorGroupElector(backendEx)))
        val result = (aspect.aroundLeader(pjp) as Mono<*>).block()

        result shouldBeEqualTo SAMPLE_RESULT
    }

    @Test
    fun `group mono 결과가 Mono 타입 검증`() {
        configureMonoJoinPoint("runMono", MonoGroupServiceImpl())
        every { pjp.proceed() } returns Mono.just(SAMPLE_RESULT)

        val aspect = newAspect(fakeGroupFactory(ElectedGroupElector()))
        val raw = aspect.aroundLeader(pjp)

        raw.shouldBeInstanceOf<Mono<*>>()
    }

    @Test
    fun `group flux 미지원 - error stream 반환하고 본문 미실행`() {
        configureStreamJoinPoint("runFlux", StreamGroupServiceImpl())

        val aspect = newAspect(fakeGroupFactory(ElectedGroupElector()))
        val flux = aspect.aroundLeader(pjp) as Flux<*>

        assertFailsWith<LeaderGroupElectionException> { flux.collectList().block() }
    }

    @Test
    fun `group flow 미지원 - collection 시 예외 반환하고 본문 미실행`() = runTest {
        configureStreamJoinPoint("runFlow", StreamGroupServiceImpl())

        val aspect = newAspect(fakeGroupFactory(ElectedGroupElector()))
        val flow = aspect.aroundLeader(pjp) as Flow<*>

        assertFailsWith<LeaderGroupElectionException> { flow.toList() }
    }
}
