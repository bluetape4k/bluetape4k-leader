package io.bluetape4k.leader.spring.metrics

import io.bluetape4k.leader.micrometer.LeaderMetricTagOptions
import io.bluetape4k.leader.micrometer.LeaderObservationOptions
import io.bluetape4k.leader.micrometer.MicrometerObservationLeaderAopMetricsRecorder
import io.bluetape4k.leader.micrometer.MicrometerObservationLeaderElectionListener
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopAutoConfiguration
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.properties.LeaderTracingProperties
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.micrometer.observation.ObservationRegistry
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.SearchStrategy
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Role
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Observation API가 있고 observability tracing이 활성화될 때 leader observation recorder/listener를 등록합니다. */
@AutoConfiguration(
    after = [LeaderMicrometerAutoConfiguration::class],
    afterName = ["org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration"],
    before = [LeaderAopAutoConfiguration::class],
)
@ConditionalOnClass(
    name = [
        "io.micrometer.observation.ObservationRegistry",
        "io.bluetape4k.leader.micrometer.MicrometerObservationLeaderAopMetricsRecorder",
    ]
)
@ConditionalOnProperty(
    prefix = "bluetape4k.leader.observability",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(LeaderProperties::class, LeaderAopProperties::class)
class LeaderObservationAutoConfiguration {

    companion object: KLogging()

    @Bean(name = [LEASE_EXTENSION_OBSERVATION_SCOPE_OWNER_BEAN_NAME])
    @ConditionalOnBean(ObservationRegistry::class)
    @Conditional(ObservationRegistryNotNoopCondition::class)
    @ConditionalOnMissingBean(
        value = [LeaseExtensionObservationScopeOwner::class],
        search = SearchStrategy.CURRENT,
    )
    @ConditionalOnProperty(
        prefix = "bluetape4k.leader.observability.tracing",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    internal fun leaseExtensionObservationScopeOwner(
        registry: ObservationRegistry,
    ): LeaseExtensionObservationScopeOwner = LeaseExtensionObservationScopeOwner(registry)

    /** tracing properties와 tag rule을 사용해 Observation-backed AOP recorder를 등록합니다. */
    @Bean
    @ConditionalOnBean(ObservationRegistry::class)
    @ConditionalOnMissingBean(MicrometerObservationLeaderAopMetricsRecorder::class)
    @ConditionalOnProperty(
        prefix = "bluetape4k.leader.observability.tracing",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun micrometerObservationLeaderAopMetricsRecorder(
        registry: ObservationRegistry,
        properties: LeaderProperties,
        aopProperties: LeaderAopProperties,
    ): MicrometerObservationLeaderAopMetricsRecorder =
        MicrometerObservationLeaderAopMetricsRecorder(
            registry = registry,
            options = properties.observability.tracing.toOptions(aopProperties.metrics.tags.toMicrometerOptions()),
        )

    /** tracing properties와 tag rule을 사용해 Observation-backed election listener를 등록합니다. */
    @Bean
    @ConditionalOnBean(ObservationRegistry::class)
    @ConditionalOnMissingBean(MicrometerObservationLeaderElectionListener::class)
    @ConditionalOnProperty(
        prefix = "bluetape4k.leader.observability.tracing",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun micrometerObservationLeaderElectionListener(
        registry: ObservationRegistry,
        properties: LeaderProperties,
        aopProperties: LeaderAopProperties,
    ): MicrometerObservationLeaderElectionListener =
        MicrometerObservationLeaderElectionListener(
            registry = registry,
            options = properties.observability.tracing.toOptions(aopProperties.metrics.tags.toMicrometerOptions()),
        )

    /** registry post-processing 이후 정상 registry에만 context별 registration handle을 추가합니다. */
    @Bean
    @ConditionalOnBean(ObservationRegistry::class)
    @Conditional(ObservationRegistryNotNoopCondition::class)
    @ConditionalOnProperty(
        prefix = "bluetape4k.leader.observability.tracing",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun leaseExtensionObserverRegistrationCoordinator(
        beanFactory: ConfigurableListableBeanFactory,
        properties: LeaderProperties,
        aopProperties: LeaderAopProperties,
    ): SmartInitializingSingleton =
        ObservationRegistryLeaseExtensionCoordinator(
            beanFactory = beanFactory,
            options = properties.observability.tracing.toOptions(aopProperties.metrics.tags.toMicrometerOptions()),
        )
}

/**
 * Boot의 [ObservationRegistry] post-processor가 handler와 customizer를 적용한 뒤 registration을 생성합니다.
 */
internal class ObservationRegistryLeaseExtensionCoordinator(
    private val beanFactory: ConfigurableListableBeanFactory,
    private val options: LeaderObservationOptions,
): SmartInitializingSingleton, DisposableBean {

    private companion object: KLogging() {
        private const val LEASE_EXTENSION_REGISTRATION_BEAN_NAME = "leaseExtensionObserverRegistration"
    }

    private val lock = ReentrantLock()
    private var registration: LeaseExtensionObservationRegistrationManager.ManagedRegistration? = null
    private var owner: LeaseExtensionObservationScopeOwner? = null

    override fun afterSingletonsInstantiated() {
        if (!beanFactory.containsLocalBean(LEASE_EXTENSION_OBSERVATION_SCOPE_OWNER_BEAN_NAME)) return
        beanFactory.getBean<LeaseExtensionObservationScopeOwner>(LEASE_EXTENSION_OBSERVATION_SCOPE_OWNER_BEAN_NAME)
            .let(::register)
    }

    private fun register(owner: LeaseExtensionObservationScopeOwner) {
        val registry = owner.registry
        if (registry.isNoop) return

        lock.withLock {
            if (registration != null || beanFactory.containsSingleton(LEASE_EXTENSION_REGISTRATION_BEAN_NAME)) return

            val handle = LeaseExtensionObservationRegistrationManager.acquire(registry, options)
            try {
                owner.activate(handle.scope)
                beanFactory.registerSingleton(LEASE_EXTENSION_REGISTRATION_BEAN_NAME, handle)
                registration = handle
                this.owner = owner
                log.info { "Registered lease extension observation: handle=$handle" }
            } catch (ex: IllegalStateException) {
                owner.clear(handle.scope)
                handle.close()
                throw ex
            }
        }
    }

    override fun destroy() {
        lock.withLock {
            log.info { "Destroy $this" }
            val handle = registration
            if (handle != null) {
                owner?.clear(handle.scope)
                handle.close()
            }
            registration = null
            owner = null
        }
    }
}

private fun LeaderTracingProperties.toOptions(tagOptions: LeaderMetricTagOptions): LeaderObservationOptions =
    LeaderObservationOptions(
        includeLockName = includeLockName,
        includeLeaderId = includeLeaderId,
        includeExceptionDetails = includeExceptionDetails,
        tagOptions = tagOptions,
    )
