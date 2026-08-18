package io.bluetape4k.leader.spring.metrics

import io.micrometer.observation.ObservationRegistry
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.ConfigurationCondition
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * [ObservationRegistry] 후보가 있는지 확인하되 registry instance를 조기 생성하지 않습니다.
 *
 * 실제 [ObservationRegistry.isNoop] 판정은 registry가 모든 [org.springframework.beans.factory.config.BeanPostProcessor]
 * 를 통과한 뒤 [ObservationRegistryLeaseExtensionCoordinator]가 수행합니다.
 */
internal class ObservationRegistryNotNoopCondition : ConfigurationCondition {

    override fun getConfigurationPhase(): ConfigurationCondition.ConfigurationPhase =
        ConfigurationCondition.ConfigurationPhase.REGISTER_BEAN

    override fun matches(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): Boolean {
        val beanFactory = context.beanFactory ?: return false
        return hasObservationRegistryDefinition(beanFactory)
    }

    private fun hasObservationRegistryDefinition(beanFactory: ConfigurableListableBeanFactory): Boolean {
        if (beanFactory.getBeanNamesForType(ObservationRegistry::class.java, false, false).isNotEmpty()) return true

        return when (val parent = beanFactory.parentBeanFactory) {
            is ConfigurableListableBeanFactory -> hasObservationRegistryDefinition(parent)
            is ListableBeanFactory -> parent
                .getBeanNamesForType(ObservationRegistry::class.java, false, false)
                .isNotEmpty()
            else -> false
        }
    }
}
