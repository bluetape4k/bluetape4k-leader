package io.bluetape4k.leader.spring.aop

import io.bluetape4k.leader.annotation.LeaderElection
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopAutoConfiguration
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopFactoryAutoConfiguration
import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * Freefair CTW (compile-time weaving) + Spring AOP double-fire 방지 검증.
 *
 * ## 배경
 * `leader-spring-boot` 는 Freefair AspectJ post-compile-weaving 사용.
 * `@EnableAspectJAutoProxy` 를 **함께 사용하면** advice 2회 발화 (double-fire) 위험이 있다.
 * `LeaderAopAutoConfiguration` 은 `@EnableAspectJAutoProxy` 를 포함하지 않아야 한다.
 *
 * ## 검증 한계
 * Freefair CTW는 main sourceSet에만 적용되고 test sourceSet은 weaving 대상이 아니다.
 * `ApplicationContextRunner` 환경에서 test 클래스의 CTW advice 발화 카운팅은 불가능하다.
 * 대신 Spring context 설정의 정확성(double-fire 방지 구조)을 검증한다.
 *
 * ## double-fire 방지 핵심 보증
 * `@EnableAspectJAutoProxy`가 없음 → `internalAutoProxyCreator` 빈 미등록 → Spring proxy 미생성
 * → CTW advice만 1회 적용 → double-fire 없음
 *
 * @see LeaderAopAutoConfiguration
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdviceFireCountTest {

    companion object : KLogging()

    class AdviceFireTestService {
        @LeaderElection(name = "fire-count-test")
        fun doWork(): String = "done"
    }

    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                LeaderAopFactoryAutoConfiguration::class.java,
                LeaderAopAutoConfiguration::class.java,
            )
        )
        .withBean(AdviceFireTestService::class.java)

    @Test
    fun `@LeaderElection 메서드가 정상 결과를 반환한다`() {
        runner.run { ctx ->
            val svc = ctx.getBean(AdviceFireTestService::class.java)
            svc.doWork() shouldBeEqualTo "done"
        }
    }

    @Test
    fun `두 번 호출해도 정상 결과 — double-fire로 인한 예외 없음`() {
        runner.run { ctx ->
            val svc = ctx.getBean(AdviceFireTestService::class.java)
            svc.doWork() shouldBeEqualTo "done"
            svc.doWork() shouldBeEqualTo "done"
        }
    }

    @Test
    fun `LeaderAopAutoConfiguration이 @EnableAspectJAutoProxy를 포함하지 않음 — double-fire 미발생`() {
        runner.run { ctx ->
            // @EnableAspectJAutoProxy 활성 시 Spring 이 internalAutoProxyCreator 빈을 등록한다.
            // 본 AutoConfig 에는 없어야 double-fire 가 발생하지 않는다.
            val hasAutoProxy = ctx.containsBeanDefinition("org.springframework.aop.config.internalAutoProxyCreator")
            hasAutoProxy shouldBeEqualTo false
        }
    }

    @Test
    fun `recorder 없을 때도 메서드가 정상 동작 — fast-path`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    LeaderAopFactoryAutoConfiguration::class.java,
                    LeaderAopAutoConfiguration::class.java,
                )
            )
            .withBean(AdviceFireTestService::class.java)
            .run { ctx ->
                val svc = ctx.getBean(AdviceFireTestService::class.java)
                svc.doWork() shouldBeEqualTo "done"
            }
    }
}
