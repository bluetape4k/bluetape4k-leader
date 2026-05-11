package io.bluetape4k.leader.spring.aop.validator

import io.bluetape4k.leader.annotation.LeaderElection
import io.bluetape4k.leader.annotation.LeaderGroupElection
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.logging.KLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import io.bluetape4k.assertions.shouldBeEqualTo
import org.aspectj.lang.annotation.Aspect
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.assertNotFails
import org.junit.jupiter.api.Test
import org.springframework.core.annotation.AliasFor
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

// ── #84: 메타 어노테이션 테스트용 composed annotations ──

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@LeaderElection(name = "")
annotation class ComposedLeaderElection(
    @get:AliasFor(annotation = LeaderElection::class, attribute = "name")
    val name: String = "composed-default-job",
)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@LeaderGroupElection(name = "", maxLeaders = 3)
annotation class ComposedGroupElection(
    @get:AliasFor(annotation = LeaderGroupElection::class, attribute = "name")
    val name: String = "composed-group-job",
    @get:AliasFor(annotation = LeaderGroupElection::class, attribute = "maxLeaders")
    val maxLeaders: Int = 3,
)

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
        assertNotFails { bpp.postProcessAfterInitialization(Sample(), "sample") }
    }

    @Test
    fun `final method strict true - startup fail`() {
        class Sample {
            @LeaderElection(name = "x")
            // Kotlin default = final
            fun finalMethod() {}
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = true, spel = spel)
        assertFailsWith<IllegalStateException> { bpp.postProcessAfterInitialization(Sample(), "sample") }
    }

    @Test
    fun `final method strict false - WARN 만 (throw 안 함)`() {
        class Sample {
            @LeaderElection(name = "x")
            fun finalMethod() {}
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = false, spel = spel)
        assertNotFails { bpp.postProcessAfterInitialization(Sample(), "sample") }
    }

    @Test
    fun `maxLeaders 1 - strict 무관 항상 fail`() {
        class Sample {
            @LeaderGroupElection(name = "x", maxLeaders = 1)
            open fun bad() {}
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = false, spel = spel)
        assertFailsWith<IllegalArgumentException> { bpp.postProcessAfterInitialization(Sample(), "sample") }
    }

    @Test
    fun `잘못된 SpEL syntax - strict 무관 startup fail`() {
        class Sample {
            @LeaderElection(name = "'unclosed string")  // SpEL 문법 오류 — 닫히지 않은 따옴표
            open fun bad() {}
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = false, spel = spel)
        assertFailsWith<IllegalStateException> { bpp.postProcessAfterInitialization(Sample(), "sample") }
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
        assertNotFails { bpp.postProcessAfterInitialization(Sample(), "sample") }
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
    fun `suspend method strict true - 위반 없음 (#90 지원)`() {
        class SampleSuspend {
            @LeaderElection(name = "suspend-job")
            open suspend fun doWork(): String = "ok"
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = true, spel = spel)
        assertNotFails { bpp.postProcessAfterInitialization(SampleSuspend(), "sample") }
    }

    @Test
    fun `suspend method strict false - 위반 없음 (#90 지원)`() {
        class SampleSuspend {
            @LeaderElection(name = "suspend-job")
            open suspend fun doWork(): String = "ok"
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = false, spel = spel)
        assertNotFails { bpp.postProcessAfterInitialization(SampleSuspend(), "sample") }
    }

    @Test
    fun `Mono 반환 타입 - 위반 없음 (#91 이후 지원)`() {
        class SampleMono {
            @LeaderElection(name = "mono-job")
            open fun process(): Mono<String> = Mono.just("ok")
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = true, spel = spel)
        assertNotFails { bpp.postProcessAfterInitialization(SampleMono(), "sample") }
    }

    @Test
    fun `Flux 반환 타입 strict true - startup fail`() {
        class SampleFlux {
            @LeaderElection(name = "flux-job")
            open fun process(): Flux<String> = Flux.just("ok")
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = true, spel = spel)
        assertFailsWith<IllegalStateException> { bpp.postProcessAfterInitialization(SampleFlux(), "sample") }
    }

    @Test
    fun `Flow 반환 타입 strict true - startup fail`() {
        class SampleFlow {
            @LeaderElection(name = "flow-job")
            open fun process(): Flow<String> = flowOf("ok")
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = true, spel = spel)
        assertFailsWith<IllegalStateException> { bpp.postProcessAfterInitialization(SampleFlow(), "sample") }
    }

    // ── #79 R12: Future / CompletableFuture / Deferred 차단 ──

    @Test
    fun `CompletableFuture 반환 타입 strict true - startup fail (R12)`() {
        class SampleFuture {
            @LeaderElection(name = "future-job")
            open fun process(): java.util.concurrent.CompletableFuture<String> =
                java.util.concurrent.CompletableFuture.completedFuture("ok")
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = true, spel = spel)
        assertFailsWith<IllegalStateException> { bpp.postProcessAfterInitialization(SampleFuture(), "sample") }
    }

    @Test
    fun `Future 반환 타입 strict true - startup fail (R12)`() {
        class SampleFuture {
            @LeaderElection(name = "future-job")
            open fun process(): java.util.concurrent.Future<String> =
                java.util.concurrent.CompletableFuture.completedFuture("ok")
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = true, spel = spel)
        assertFailsWith<IllegalStateException> { bpp.postProcessAfterInitialization(SampleFuture(), "sample") }
    }

    @Test
    fun `Deferred 반환 타입 strict true - startup fail (R12)`() {
        class SampleDeferred {
            @LeaderElection(name = "deferred-job")
            open fun process(): kotlinx.coroutines.Deferred<String> =
                kotlinx.coroutines.CompletableDeferred("ok")
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = true, spel = spel)
        assertFailsWith<IllegalStateException> { bpp.postProcessAfterInitialization(SampleDeferred(), "sample") }
    }

    @Test
    fun `CompletableFuture 반환 타입 strict false - WARN 만 (throw 안 함)`() {
        class SampleFuture {
            @LeaderElection(name = "future-job")
            open fun process(): java.util.concurrent.CompletableFuture<String> =
                java.util.concurrent.CompletableFuture.completedFuture("ok")
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = false, spel = spel)
        assertNotFails { bpp.postProcessAfterInitialization(SampleFuture(), "sample") }
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

    // ── #84: 메타 어노테이션(@AliasFor) 지원 ──

    @Test
    fun `composed @LeaderElection - BPP 검출 및 통과`() {
        class SampleComposed {
            @ComposedLeaderElection(name = "composed-job")
            open fun run(): String = "ok"
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = true, spel = spel)
        assertNotFails { bpp.postProcessAfterInitialization(SampleComposed(), "sample") }
    }

    @Test
    fun `composed @LeaderElection final method - BPP 검출 및 strict fail`() {
        class SampleComposedFinal {
            @ComposedLeaderElection(name = "composed-job")
            fun run(): String = "ok"  // final
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = true, spel = spel)
        assertFailsWith<IllegalStateException> { bpp.postProcessAfterInitialization(SampleComposedFinal(), "sample") }
    }

    @Test
    fun `composed @LeaderElection 잘못된 SpEL - strict 무관 startup fail`() {
        class SampleBadSpel {
            @ComposedLeaderElection(name = "'unclosed")
            open fun run(): String = "ok"
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = false, spel = spel)
        assertFailsWith<IllegalStateException> { bpp.postProcessAfterInitialization(SampleBadSpel(), "sample") }
    }

    @Test
    fun `composed @LeaderGroupElection maxLeaders 1 - strict 무관 startup fail`() {
        class SampleBadGroup {
            @ComposedGroupElection(name = "group-job", maxLeaders = 1)
            open fun run(): String = "ok"
        }
        val bpp = LeaderAnnotationValidatorBeanPostProcessor(strict = false, spel = spel)
        assertFailsWith<IllegalArgumentException> { bpp.postProcessAfterInitialization(SampleBadGroup(), "sample") }
    }
}
