package io.bluetape4k.leader.spring.aop.validator

import io.bluetape4k.leader.spring.aop.LeaderElection
import io.bluetape4k.leader.spring.aop.LeaderGroupElection
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

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
}
