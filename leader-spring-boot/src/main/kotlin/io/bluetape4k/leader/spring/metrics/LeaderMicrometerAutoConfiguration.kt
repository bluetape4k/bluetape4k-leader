package io.bluetape4k.leader.spring.metrics

import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.micrometer.MicrometerLeaderAopMetricsRecorder
import io.bluetape4k.leader.micrometer.MicrometerObservationLeaderAopMetricsRecorder
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopAutoConfiguration
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopFactoryAutoConfiguration
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Role
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * Micrometer metrics AutoConfiguration.
 *
 * Automatically registers [MicrometerLeaderAopMetricsRecorder] when a `MeterRegistry` bean is present.
 *
 * ## AutoConfig Order
 * ```
 * LeaderAopFactoryAutoConfiguration (backend factories)
 *   ↓
 * LeaderMicrometerAutoConfiguration  ← this class
 *   ↓
 * LeaderAopAutoConfiguration (Aspect + BPP)
 * ```
 */
@AutoConfiguration(
    after = [LeaderAopFactoryAutoConfiguration::class],
    before = [LeaderAopAutoConfiguration::class],
)
@ConditionalOnClass(name = [
    "io.micrometer.core.instrument.MeterRegistry",
    "io.bluetape4k.leader.micrometer.MicrometerLeaderAopMetricsRecorder",
])
@ConditionalOnProperty(
    prefix = "bluetape4k.leader.aop.metrics",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class LeaderMicrometerAutoConfiguration {

    /**
     * Automatically registers [MicrometerLeaderAopMetricsRecorder] only when a `MeterRegistry` bean is present
     * and the user has not registered a non-Observation [LeaderAopMetricsRecorder] directly.
     *
     * Observation recorders are complementary tracing hooks, so they do not suppress the default meter recorder.
     */
    @Bean
    @ConditionalOnBean(MeterRegistry::class)
    @ConditionalOnMissingBean(MicrometerLeaderAopMetricsRecorder::class)
    @Conditional(DefaultMeterRecorderCondition::class)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun micrometerLeaderAopMetricsRecorder(registry: MeterRegistry): MicrometerLeaderAopMetricsRecorder =
        MicrometerLeaderAopMetricsRecorder(registry)
}

private class DefaultMeterRecorderCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        val beanFactory = context.beanFactory as? ListableBeanFactory ?: return true
        val recorderNames = beanFactory.getBeanNamesForType(LeaderAopMetricsRecorder::class.java, true, false)

        return recorderNames.none { name ->
            val recorderType = beanFactory.getType(name, false)
            recorderType == null ||
                    !MicrometerObservationLeaderAopMetricsRecorder::class.java.isAssignableFrom(recorderType)
        }
    }
}
