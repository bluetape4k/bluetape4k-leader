package io.bluetape4k.leader.spring.aop.autoconfigure

import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.spring.aop.LeaderAspectOrder
import io.bluetape4k.leader.spring.aop.LeaderBeanSelector
import io.bluetape4k.leader.spring.aop.LeaderElectionAspect
import io.bluetape4k.leader.spring.aop.LeaderGroupElectionAspect
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.leader.spring.aop.util.LockNameValidator
import io.bluetape4k.leader.spring.aop.validator.LeaderAnnotationValidatorBeanPostProcessor
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Role
import org.springframework.core.annotation.Order

/**
 * AutoConfig Phase 2 — Aspect / BPP / SpEL / properties 등록.
 *
 * ## [Codex H3] Phase 분리 패턴
 * `@AutoConfiguration(after = LeaderAopFactoryAutoConfiguration::class)` 로 factory 등록 이후 평가.
 * `@ConditionalOnBean(LeaderElectionFactory)` — backend factory 가 하나도 등록 안 됐으면 본 autoconfig 비활성.
 *
 * ## CTW (Freefair post-compile weaving) 전용
 * `@EnableAspectJAutoProxy` 미사용 — compile-time weaving 이 Spring AOP runtime proxy 를 대체.
 * Aspect 는 `@Bean` 으로 등록만 하면 CTW 가 자동 적용.
 */
@AutoConfiguration(after = [LeaderAopFactoryAutoConfiguration::class])
@ConditionalOnClass(name = ["org.aspectj.lang.annotation.Aspect"])
@ConditionalOnBean(LeaderElectorFactory::class)
@ConditionalOnProperty(prefix = "bluetape4k.leader.aop", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(LeaderAopProperties::class)
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
}
