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
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.properties.LeaderGroupProperties
import io.bluetape4k.leader.spring.scheduling.LeaderScheduledPolicyRegistry
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.SearchStrategy
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Role
import org.springframework.core.annotation.Order

/**
 * `LeaderAopAutoConfiguration`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 */
@AutoConfiguration(after = [LeaderAopFactoryAutoConfiguration::class])
@ConditionalOnClass(name = ["org.aspectj.lang.annotation.Aspect"])
@ConditionalOnBean(LeaderElectorFactory::class)
@ConditionalOnProperty(prefix = "bluetape4k.leader.aop", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(LeaderAopProperties::class, LeaderProperties::class)
class LeaderAopAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(search = SearchStrategy.CURRENT)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun leaderBeanSelector(beanFactory: BeanFactory): LeaderBeanSelector =
        LeaderBeanSelector(beanFactory)

    @Bean
    @ConditionalOnMissingBean(search = SearchStrategy.CURRENT)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun leaderAopSpelExpressionEvaluator(
        beanFactory: ConfigurableBeanFactory,
        props: LeaderAopProperties,
    ): SpelExpressionEvaluator = SpelExpressionEvaluator(
        embeddedValueResolver = { value -> beanFactory.resolveEmbeddedValue(value) },
        allowMethodInvocation = props.spel.allowMethodInvocation,
    )

    @Bean
    @ConditionalOnMissingBean(search = SearchStrategy.CURRENT)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun leaderLockNameValidator(
        beanFactory: ConfigurableBeanFactory,
        props: LeaderAopProperties,
    ): LockNameValidator {
        // 선택적 application name이 없으면 `${spring.application.name:}:`가 `:`로
        // 해석됩니다. 이는 namespace prefix가 아닌 placeholder 잔여값이므로
        // 기본값에서만 제거하고 명시적 custom prefix는 그대로 유지합니다.
        val resolvedPrefix = beanFactory.resolveEmbeddedValue(props.lockNamePrefix).orEmpty()
        val effectivePrefix = if (
            props.lockNamePrefix == LeaderAopProperties.DEFAULT_LOCK_NAME_PREFIX &&
            resolvedPrefix == ":"
        ) {
            ""
        } else {
            resolvedPrefix
        }
        return LockNameValidator(prefix = effectivePrefix)
    }

    @Bean
    @Order(LeaderAspectOrder.AOP_ORDER)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @ConditionalOnMissingBean(search = SearchStrategy.CURRENT)
    fun leaderElectionAspect(
        beanSelector: LeaderBeanSelector,
        props: LeaderAopProperties,
        spel: SpelExpressionEvaluator,
        lockNameValidator: LockNameValidator,
        recordersProvider: ObjectProvider<LeaderAopMetricsRecorder>,
        scheduledPolicyRegistryProvider: ObjectProvider<LeaderScheduledPolicyRegistry>,
    ): LeaderElectionAspect = LeaderElectionAspect(
        beanSelector = beanSelector,
        props = props,
        spel = spel,
        lockNameValidator = lockNameValidator,
        recorders = recordersProvider.orderedStream().toList(),
        scheduledPolicyRegistry = scheduledPolicyRegistryProvider.getIfAvailable(),
    ).apply { observationScopeOwner = beanSelector.observationScopeOwner() }

    /** `0.5.0`에서 공개된 scheduled-policy 이전의 JVM factory descriptor를 보존합니다. */
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
        scheduledPolicyRegistry = null,
    ).apply { observationScopeOwner = beanSelector.observationScopeOwner() }

    @Bean
    @Order(LeaderAspectOrder.AOP_ORDER)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @ConditionalOnMissingBean(search = SearchStrategy.CURRENT)
    fun leaderGroupElectionAspect(
        beanSelector: LeaderBeanSelector,
        props: LeaderAopProperties,
        spel: SpelExpressionEvaluator,
        lockNameValidator: LockNameValidator,
        recordersProvider: ObjectProvider<LeaderAopMetricsRecorder>,
        leaderProperties: LeaderProperties,
    ): LeaderGroupElectionAspect = LeaderGroupElectionAspect(
        beanSelector = beanSelector,
        props = props,
        spel = spel,
        lockNameValidator = lockNameValidator,
        recorders = recordersProvider.orderedStream().toList(),
        groupProperties = leaderProperties.group,
    ).apply { observationScopeOwner = beanSelector.observationScopeOwner() }

    /** `useDbTime` 정책 추가 전에 공개된 다섯 인자 factory 메서드 descriptor를 보존합니다. */
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
        groupProperties = LeaderGroupProperties(),
    ).apply { observationScopeOwner = beanSelector.observationScopeOwner() }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @ConditionalOnMissingBean
    fun leaderAnnotationValidatorBeanPostProcessor(
        props: LeaderAopProperties,
        spel: SpelExpressionEvaluator,
    ): LeaderAnnotationValidatorBeanPostProcessor =
        LeaderAnnotationValidatorBeanPostProcessor(strict = props.strict, spel = spel)
}
