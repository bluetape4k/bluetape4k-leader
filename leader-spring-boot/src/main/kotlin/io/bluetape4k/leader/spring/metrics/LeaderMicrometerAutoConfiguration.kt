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
 * `선언`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
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
     * `leaderMetricTagSanitizer` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    @Bean
    @ConditionalOnMissingBean(LeaderMetricTagSanitizer::class)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun leaderMetricTagSanitizer(props: LeaderAopProperties): LeaderMetricTagSanitizer =
        LeaderMetricTagSanitizer.from(props.metrics.tags.toMicrometerOptions())

    /**
     * `micrometerLeaderAopMetricsRecorder` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
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

    /**
     * Preserves the one-argument factory method published in 0.4.0. It is kept
     * as a normal compatibility method so Spring registers only the tagged bean.
     */
    @Deprecated("Use the tagged overload supplied by Spring auto-configuration")
    fun micrometerLeaderAopMetricsRecorder(registry: MeterRegistry): MicrometerLeaderAopMetricsRecorder =
        MicrometerLeaderAopMetricsRecorder(registry)
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
