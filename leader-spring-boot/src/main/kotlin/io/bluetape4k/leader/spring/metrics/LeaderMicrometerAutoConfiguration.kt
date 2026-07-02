package io.bluetape4k.leader.spring.metrics

import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.micrometer.MicrometerLeaderAopMetricsRecorder
import io.bluetape4k.leader.micrometer.MicrometerObservationLeaderAopMetricsRecorder
import io.bluetape4k.leader.micrometer.LeaderMetricTagMode
import io.bluetape4k.leader.micrometer.LeaderMetricTagOptions
import io.bluetape4k.leader.micrometer.LeaderMetricTagRule
import io.bluetape4k.leader.micrometer.LeaderMetricTagSanitizer
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopAutoConfiguration
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopFactoryAutoConfiguration
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
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
@EnableConfigurationProperties(LeaderAopProperties::class)
class LeaderMicrometerAutoConfiguration {

    /**
     * Builds the default leader metric tag sanitizer from Spring properties.
     */
    @Bean
    @ConditionalOnMissingBean(LeaderMetricTagSanitizer::class)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun leaderMetricTagSanitizer(props: LeaderAopProperties): LeaderMetricTagSanitizer =
        LeaderMetricTagSanitizer.from(props.metrics.tags.toMicrometerOptions())

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
    fun micrometerLeaderAopMetricsRecorder(
        registry: MeterRegistry,
        tagSanitizer: LeaderMetricTagSanitizer,
    ): MicrometerLeaderAopMetricsRecorder =
        MicrometerLeaderAopMetricsRecorder(registry, tagSanitizer)
}

internal fun LeaderAopProperties.Metrics.Tags.toMicrometerOptions(): LeaderMetricTagOptions =
    LeaderMetricTagOptions(
        lockName = lockName.toMicrometerRule(),
        leaderId = leaderId.toMicrometerRule(),
        backendName = backendName.toMicrometerRule(),
        defaultRule = defaultRule.toMicrometerRule(),
    )

private fun LeaderAopProperties.Metrics.TagRule.toMicrometerRule(): LeaderMetricTagRule =
    LeaderMetricTagRule(
        mode = mode.toMicrometerMode(),
        allowList = allowList,
        denyList = denyList,
        hashLength = hashLength,
        maxLength = maxLength,
        redactedValue = redactedValue,
    )

private fun LeaderAopProperties.Metrics.TagMode.toMicrometerMode(): LeaderMetricTagMode =
    when (this) {
        LeaderAopProperties.Metrics.TagMode.REDACT -> LeaderMetricTagMode.REDACT
        LeaderAopProperties.Metrics.TagMode.RAW -> LeaderMetricTagMode.RAW
        LeaderAopProperties.Metrics.TagMode.HASH -> LeaderMetricTagMode.HASH
        LeaderAopProperties.Metrics.TagMode.TRUNCATE -> LeaderMetricTagMode.TRUNCATE
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
