package io.bluetape4k.leader.spring.scheduling

import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.spring.aop.LeaderBeanSelector
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopAutoConfiguration
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopFactoryAutoConfiguration
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Role

/**
 * scheduling policy metadata를 AOP 경로보다 먼저 구성합니다.
 */
@AutoConfiguration(
    after = [LeaderAopFactoryAutoConfiguration::class],
    before = [LeaderAopAutoConfiguration::class],
)
@ConditionalOnClass(name = [
    "org.aspectj.lang.annotation.Aspect",
    "org.springframework.scheduling.annotation.Scheduled",
])
@ConditionalOnBean(LeaderElectorFactory::class)
@ConditionalOnProperty(
    prefix = LeaderScheduledPolicyProperties.PREFIX,
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
@EnableConfigurationProperties(LeaderScheduledPolicyProperties::class)
class LeaderScheduledPolicyAutoConfiguration {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun leaderScheduledPolicyRegistry(
        properties: LeaderScheduledPolicyProperties,
    ): LeaderScheduledPolicyRegistry = LeaderScheduledPolicyRegistry(properties.policies)

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun leaderScheduledPolicyBeanPostProcessor(
        registry: LeaderScheduledPolicyRegistry,
        properties: LeaderScheduledPolicyProperties,
        aopProperties: LeaderAopProperties,
        beanSelector: LeaderBeanSelector,
        spel: SpelExpressionEvaluator,
    ): LeaderScheduledPolicyBeanPostProcessor = LeaderScheduledPolicyBeanPostProcessor(
        registry = registry,
        properties = properties,
        aopProperties = aopProperties,
        beanSelector = beanSelector,
        spel = spel,
    )
}
