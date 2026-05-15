package io.bluetape4k.leader.spring.observability

import io.bluetape4k.leader.LeaderElectionEventPublisher
import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.leader.LeaderElectionListenerRegistry
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.spring.LeaderElectionAutoConfiguration
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopAutoConfiguration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Role

/**
 * Spring Boot observability auto-configuration for leader election.
 *
 * ## Behavior / Contract
 * - Registers a JVM-local [LeaderElectionStatusRegistry].
 * - Exposes a publisher-only fallback [LeaderElectionEventPublisher] when no publisher bean exists.
 * - Attaches the registry to listener-aware leader beans when they are present.
 */
@AutoConfiguration(
    after = [
        LeaderElectionAutoConfiguration::class,
        LeaderAopAutoConfiguration::class,
    ],
)
@ConditionalOnClass(LeaderElector::class)
@ConditionalOnProperty(
    prefix = "bluetape4k.leader.observability",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(LeaderProperties::class)
class LeaderElectionObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun leaderElectionStatusRegistry(props: LeaderProperties): LeaderElectionStatusRegistry =
        LeaderElectionStatusRegistry(props.observability.lockNames)

    @Bean("leaderElectionEventPublisher")
    @ConditionalOnMissingBean(LeaderElectionEventPublisher::class)
    @Role(BeanDefinition.ROLE_APPLICATION)
    fun leaderElectionEventPublisher(
        registry: LeaderElectionStatusRegistry,
    ): LeaderElectionEventPublisher =
        LeaderElectionObservedEventPublisher(registry)

    @Bean
    @ConditionalOnBean(LeaderElectionStatusRegistry::class)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun leaderElectionStatusRegistryRegistrar(
        listeners: ObjectProvider<LeaderElectionListener>,
        listenerRegistries: ObjectProvider<LeaderElectionListenerRegistry>,
    ): SmartInitializingSingleton =
        SmartInitializingSingleton {
            val listenerSnapshot = listeners.orderedStream().toList()
            listenerRegistries.orderedStream().forEach { listenerRegistry ->
                listenerSnapshot.forEach { listener ->
                    listenerRegistry.addListener(listener)
                }
            }
        }
}
