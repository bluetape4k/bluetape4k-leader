package io.bluetape4k.leader.spring.aop.validator

import io.bluetape4k.leader.annotation.LeaderElection
import io.bluetape4k.leader.annotation.LeaderGroupElection
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.logging.KLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.amshove.kluent.shouldBeEqualTo
import org.aspectj.lang.annotation.Aspect
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * [LeaderAnnotationValidatorBeanPostProcessor] — footgun 검출 매트릭스 (T5.5).
 *
 * 검증 항목:
 * - final / private 메서드 검출
 * - maxLeaders <= 1 (strict 무관 항상 fail)
 * - SpEL pre-parse 실패 (strict 무관 항상 fail)
 * - 정상 메서드 통과
 * - 같은 클래스 2+ annotated method WARN (best-effort self-inv)
 * - BPP self-throw 격리 (Step 3-P-Rel)
 * - suspend / Mono / Flux / Flow 반환 타입 검출 (#96)
 * - @Aspect 클래스 skip (#96)
 */
class LeaderAnnotationValidatorBeanPostProcessorTest {

    companion object: KLogging()

    private val spel = SpelExpressionEvaluator(embeddedValueResolver = { it })

    @Test
    fun `정상 method - 통과`() {
        class Sample {
            @LeaderElection(name = "ok-job")
            open fun ok() {}  // Kotlin default-final 회피 — open 명시
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = true, spel = spel)
        assertDoesNotThrow { bpp.postProcessAfterInitialization(Sample(), "sample") }
    }

    @Test
    fun `final method strict true - startup fail`() {
        class Sample {
            @LeaderElection(name = "x")
            // Kotlin default = final
            fun finalMethod() {}
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = true, spel = spel)
        assertThrows<IllegalStateException> { bpp.postProcessAfterInitialization(Sample(), "sample") }
    }

    @Test
    fun `final method strict false - WARN 만 (throw 안 함)`() {
        class Sample {
            @LeaderElection(name = "x")
            fun finalMethod() {}
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = false, spel = spel)
        assertDoesNotThrow { bpp.postProcessAfterInitialization(Sample(), "sample") }
    }

    @Test
    fun `maxLeaders 1 - strict 무관 항상 fail`() {
        class Sample {
            @LeaderGroupElection(name = "x", maxLeaders = 1)
            open fun bad() {}
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = false, spel = spel)
        assertThrows<IllegalArgumentException> { bpp.postProcessAfterInitialization(Sample(), "sample") }
    }

    @Test
    fun `잘못된 SpEL syntax - strict 무관 startup fail`() {
        class Sample {
            @LeaderElection(name = "'unclosed string")  // SpEL 문법 오류 — 닫히지 않은 따옴표
            open fun bad() {}
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = false, spel = spel)
        assertThrows<IllegalStateException> { bpp.postProcessAfterInitialization(Sample(), "sample") }
    }

    @Test
    fun `같은 클래스 2+ annotated method - WARN best-effort (throw 안 함)`() {
        class Sample {
            @LeaderElection(name = "a")
            open fun a() {}

            @LeaderElection(name = "b")
            open fun b() {}
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = true, spel = spel)
        // self-inv 자체는 strict 모드에서도 WARN 만 (정확 검출 불가)
        assertDoesNotThrow { bpp.postProcessAfterInitialization(Sample(), "sample") }
    }

    @Test
    fun `MethodInterceptor 빈 - skip (검증 대상 아님)`() {
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = true, spel = spel)
        val interceptor = FakeInterceptor()
        bpp.postProcessAfterInitialization(interceptor, "interceptor") shouldBeEqualTo interceptor
    }

    private class FakeInterceptor : org.aopalliance.intercept.MethodInterceptor {
        override fun invoke(invocation: org.aopalliance.intercept.MethodInvocation): Any? = null
    }

    // ── #96: 누락 분기 — suspend / Mono / Flux / Flow / @Aspect ──

    @Test
    fun `suspend method strict true - startup fail`() {
        class SampleSuspend {
            @LeaderElection(name = "suspend-job")
            open suspend fun doWork(): String = "ok"
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = true, spel = spel)
        assertThrows<IllegalStateException> { bpp.postProcessAfterInitialization(SampleSuspend(), "sample") }
    }

    @Test
    fun `suspend method strict false - WARN 만 (throw 안 함)`() {
        class SampleSuspend {
            @LeaderElection(name = "suspend-job")
            open suspend fun doWork(): String = "ok"
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = false, spel = spel)
        assertDoesNotThrow { bpp.postProcessAfterInitialization(SampleSuspend(), "sample") }
    }

    @Test
    fun `Mono 반환 타입 strict true - startup fail`() {
        class SampleMono {
            @LeaderElection(name = "mono-job")
            open fun process(): Mono<String> = Mono.just("ok")
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = true, spel = spel)
        assertThrows<IllegalStateException> { bpp.postProcessAfterInitialization(SampleMono(), "sample") }
    }

    @Test
    fun `Flux 반환 타입 strict true - startup fail`() {
        class SampleFlux {
            @LeaderElection(name = "flux-job")
            open fun process(): Flux<String> = Flux.just("ok")
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = true, spel = spel)
        assertThrows<IllegalStateException> { bpp.postProcessAfterInitialization(SampleFlux(), "sample") }
    }

    @Test
    fun `Flow 반환 타입 strict true - startup fail`() {
        class SampleFlow {
            @LeaderElection(name = "flow-job")
            open fun process(): Flow<String> = flowOf("ok")
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = true, spel = spel)
        assertThrows<IllegalStateException> { bpp.postProcessAfterInitialization(SampleFlow(), "sample") }
    }

    @Test
    fun `@Aspect 클래스 - skip (검증 대상 아님)`() {
        @Aspect
        class SampleAspect {
            @LeaderElection(name = "x")
            fun doSomething() {}  // final이어도 @Aspect 클래스는 통째로 skip
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = true, spel = spel)
        val aspect = SampleAspect()
        bpp.postProcessAfterInitialization(aspect, "aspect") shouldBeEqualTo aspect
    }
}
