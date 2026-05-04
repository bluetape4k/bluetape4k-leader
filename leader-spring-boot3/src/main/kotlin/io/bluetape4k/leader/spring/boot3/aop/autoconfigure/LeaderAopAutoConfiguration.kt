package io.bluetape4k.leader.spring.boot3.aop.autoconfigure

import io.bluetape4k.leader.LeaderElectionFactory
import io.bluetape4k.leader.spring.aop.LeaderAspectOrder
import io.bluetape4k.leader.spring.aop.LeaderBeanSelector
import io.bluetape4k.leader.spring.aop.LeaderElectionAspect
import io.bluetape4k.leader.spring.aop.LeaderGroupElectionAspect
import io.bluetape4k.leader.spring.aop.health.LeaderAopHealthIndicator
import io.bluetape4k.leader.spring.aop.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.leader.spring.aop.util.LockNameValidator
import io.bluetape4k.leader.spring.aop.validator.LeaderAnnotationValidatorBeanPostProcessor
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.context.annotation.Role
import org.springframework.core.annotation.Order

/**
 * AutoConfig Phase 2 — Aspect / BPP / Health / SpEL / properties 등록.
 *
 * ## [Codex H3] Phase 분리 패턴
 * `@AutoConfiguration(after = LeaderAopFactoryAutoConfiguration::class)` 로 factory 등록 이후 평가.
 * `@ConditionalOnBean(LeaderElectionFactory)` — backend factory 가 하나도 등록 안 됐으면 본 autoconfig 비활성.
 *
 * ## [T4.1a Option A] Aspect `@Bean` 직접 등록
 * `LeaderElectionAspect` / `LeaderGroupElectionAspect` 는 common 의 `final class @Aspect`.
 * 빈 subclass 미사용 — 본 autoconfig 가 `@Bean` 메서드로 직접 등록.
 *
 * ## `proxyTargetClass = true`
 * Kotlin default-final 클래스 proxy 지원 (CGLib subclass).
 *
 * ## [issue #1050] spring-boot-starter-aop 미추가
 * Boot 3 측은 Spring AOP runtime proxy 만 사용 (Boot 4 의 Freefair compile-time weaving 과
 * 동시 활성 시 advice 2회 발화 위험을 회피).
 */
@AutoConfiguration(after = [LeaderAopFactoryAutoConfiguration::class])
@ConditionalOnClass(name = ["org.aspectj.lang.annotation.Aspect"])
@ConditionalOnBean(LeaderElectionFactory::class)
@EnableConfigurationProperties(LeaderAopProperties::class)
@EnableAspectJAutoProxy(proxyTargetClass = true)
class LeaderAopAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun leaderBeanSelector(beanFactory: BeanFactory): LeaderBeanSelector =
        LeaderBeanSelector(beanFactory)

    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun leaderAopSpelExpressionEvaluator(
        beanFactory: ConfigurableBeanFactory,
        props: LeaderAopProperties,
    ): SpelExpressionEvaluator = SpelExpressionEvaluator(
        embeddedValueResolver = { value -> beanFactory.resolveEmbeddedValue(value) },
        allowMethodInvocation = props.spel.allowMethodInvocation,
    )

    @Bean
    @ConditionalOnMissingBean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun leaderLockNameValidator(
        beanFactory: ConfigurableBeanFactory,
        props: LeaderAopProperties,
    ): LockNameValidator {
        val resolvedPrefix = beanFactory.resolveEmbeddedValue(props.lockNamePrefix) ?: ""
        return LockNameValidator(prefix = resolvedPrefix)
    }

    @Bean
    @Order(LeaderAspectOrder.AOP_ORDER)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @ConditionalOnMissingBean
    fun leaderElectionAspect(
        beanSelector: LeaderBeanSelector,
        props: LeaderAopProperties,
        spel: SpelExpressionEvaluator,
        lockNameValidator: LockNameValidator,
        recordersProvider: ObjectProvider<LeaderAopMetricsRecorder>,
    ): LeaderElectionAspect = LeaderElectionAspect(
        beanSelector = beanSelector,
        props = props,
        spel = spel,
        lockNameValidator = lockNameValidator,
        recorders = recordersProvider.orderedStream().toList(),
    )

    @Bean
    @Order(LeaderAspectOrder.AOP_ORDER)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @ConditionalOnMissingBean
    fun leaderGroupElectionAspect(
        beanSelector: LeaderBeanSelector,
        props: LeaderAopProperties,
        spel: SpelExpressionEvaluator,
        lockNameValidator: LockNameValidator,
        recordersProvider: ObjectProvider<LeaderAopMetricsRecorder>,
    ): LeaderGroupElectionAspect = LeaderGroupElectionAspect(
        beanSelector = beanSelector,
        props = props,
        spel = spel,
        lockNameValidator = lockNameValidator,
        recorders = recordersProvider.orderedStream().toList(),
    )

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @ConditionalOnMissingBean
    fun leaderAnnotationValidatorBeanPostProcessor(
        props: LeaderAopProperties,
        spel: SpelExpressionEvaluator,
    ): LeaderAnnotationValidatorBeanPostProcessor =
        LeaderAnnotationValidatorBeanPostProcessor(strict = props.strict, spel = spel)

    @Bean(name = ["leaderAopHealthIndicator"])
    @ConditionalOnClass(HealthIndicator::class)
    @ConditionalOnMissingBean(name = ["leaderAopHealthIndicator"])
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun leaderAopHealthIndicator(spel: SpelExpressionEvaluator): HealthIndicator =
        LeaderAopHealthIndicator(spel)
}
