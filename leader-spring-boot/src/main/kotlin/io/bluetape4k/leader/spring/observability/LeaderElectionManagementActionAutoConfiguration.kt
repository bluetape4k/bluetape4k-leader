package io.bluetape4k.leader.spring.observability

import io.bluetape4k.leader.LeaderManagementActionObservation
import io.bluetape4k.leader.LeaderManagementActionObserver
import io.bluetape4k.leader.LeaderManagementActionRegistry
import io.bluetape4k.leader.LeaderManagementActionSurface
import io.bluetape4k.leader.micrometer.MicrometerLeaderManagementActionObserver
import io.bluetape4k.leader.spring.properties.LeaderManagementActionProperties
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Role
import kotlin.time.toKotlinDuration

/**
 * `leaderElectionActions` write surface를 명시적으로 opt-in할 때만 구성합니다.
 *
 * 기존 `leaderElection` status endpoint와는 별도 bean/ID를 사용합니다. parent
 * endpoint와 nested action property가 모두 활성화되지 않으면 registry, lifecycle,
 * web route가 생성되지 않습니다.
 */
@AutoConfiguration(after = [LeaderElectionActuatorAutoConfiguration::class])
@ConditionalOnClass(
    name = [
        "org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint",
        "org.springframework.boot.actuate.endpoint.web.WebEndpointResponse",
        "io.bluetape4k.leader.LeaderManagementActionRegistry",
    ],
)
@ConditionalOnProperty(
    prefix = "management.endpoint.leaderElection",
    name = ["enabled"],
    havingValue = "true",
)
@ConditionalOnProperty(
    prefix = "management.endpoint.leaderElection.actions",
    name = ["enabled"],
    havingValue = "true",
)
@EnableConfigurationProperties(LeaderManagementActionProperties::class)
class LeaderElectionManagementActionAutoConfiguration {

    @Bean("leaderElectionActionWebEndpoint")
    @ConditionalOnBean(LeaderManagementActionRegistry::class)
    @ConditionalOnMissingBean(LeaderElectionActionWebEndpoint::class)
    @Role(BeanDefinition.ROLE_APPLICATION)
    internal fun leaderElectionActionWebEndpoint(
        registry: LeaderManagementActionRegistry,
    ): LeaderElectionActionWebEndpoint = LeaderElectionActionWebEndpoint(registry)

    /** library-owned registry와 lifecycle은 application registry가 없을 때만 생성합니다. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(LeaderManagementActionRegistry::class)
    internal class DefaultRegistryConfiguration {

        @Bean(name = ["leaderManagementActionRegistry"], destroyMethod = "")
        @Role(BeanDefinition.ROLE_APPLICATION)
        fun leaderManagementActionRegistry(
            properties: LeaderManagementActionProperties,
            observers: ObjectProvider<LeaderManagementActionObserver>,
        ): LeaderManagementActionRegistry = LeaderManagementActionRegistry(
            observer = observers.getIfAvailable(),
            actionTimeout = properties.timeout.toKotlinDuration(),
        )

        @Bean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun leaderManagementActionLifecycle(
            registry: LeaderManagementActionRegistry,
        ): LeaderManagementActionLifecycle = LeaderManagementActionLifecycle(registry)

        /** Micrometer가 있을 때만 Spring surface decorator를 application registry에 연결합니다. */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(
            name = [
                "io.micrometer.core.instrument.MeterRegistry",
                "io.bluetape4k.leader.micrometer.MicrometerLeaderManagementActionObserver",
            ],
        )
        @ConditionalOnBean(MeterRegistry::class)
        @ConditionalOnMissingBean(LeaderManagementActionObserver::class)
        internal class MicrometerObserverConfiguration {

            @Bean("leaderManagementActionObserver")
            @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
            fun leaderManagementActionObserver(
                meterRegistry: MeterRegistry,
            ): LeaderManagementActionObserver = SpringSurfaceObserver(
                MicrometerLeaderManagementActionObserver(meterRegistry),
            )
        }
    }
}

/** core observer의 `CORE` surface를 Spring adapter 경계로 정규화합니다. */
private class SpringSurfaceObserver(
    private val delegate: LeaderManagementActionObserver,
) : LeaderManagementActionObserver {

    override fun onResult(observation: LeaderManagementActionObservation) {
        delegate.onResult(observation.withSpringSurface())
    }

    override fun onQuarantineRecovered(observation: LeaderManagementActionObservation) {
        delegate.onQuarantineRecovered(observation.withSpringSurface())
    }

    private fun LeaderManagementActionObservation.withSpringSurface(): LeaderManagementActionObservation =
        copy(surface = LeaderManagementActionSurface.SPRING)
}
