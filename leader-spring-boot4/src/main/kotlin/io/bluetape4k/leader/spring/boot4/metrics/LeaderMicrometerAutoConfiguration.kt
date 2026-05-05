package io.bluetape4k.leader.spring.boot4.metrics

import io.bluetape4k.leader.micrometer.MicrometerLeaderAopMetricsRecorder
import io.bluetape4k.leader.spring.aop.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.spring.boot4.aop.autoconfigure.LeaderAopAutoConfiguration
import io.bluetape4k.leader.spring.boot4.aop.autoconfigure.LeaderAopFactoryAutoConfiguration
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Role

/**
 * Spring Boot 4 Micrometer metrics AutoConfiguration.
 *
 * `MeterRegistry` 빈이 존재할 때 [MicrometerLeaderAopMetricsRecorder]를 자동 등록한다.
 *
 * ## Boot 4 제약
 * Boot 4에서 HealthIndicator 경로가 `org.springframework.boot.health.contributor`로 이동했다.
 * `leader-spring-boot-common`의 `LeaderAopHealthIndicator`는 Boot 3 경로 기준이므로
 * Boot 4 HealthContributor 통합은 이 PR 범위 외 (후속 PR 별도 처리 예정).
 *
 * ## AutoConfig 순서
 * ```
 * LeaderAopFactoryAutoConfiguration (backend factories)
 *   ↓
 * LeaderMicrometerAutoConfiguration  ← 본 클래스
 *   ↓
 * LeaderAopAutoConfiguration (Aspect + BPP)
 * ```
 */
@AutoConfiguration(
    after = [LeaderAopFactoryAutoConfiguration::class],
    before = [LeaderAopAutoConfiguration::class],
)
@ConditionalOnClass(name = ["io.micrometer.core.instrument.MeterRegistry"])
@ConditionalOnProperty(
    prefix = "bluetape4k.leader.aop.metrics",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class LeaderMicrometerAutoConfiguration {

    /**
     * `MeterRegistry` 빈이 존재하고 사용자가 직접 [LeaderAopMetricsRecorder]를 등록하지 않은 경우에만
     * [MicrometerLeaderAopMetricsRecorder]를 자동 등록한다.
     *
     * 사용자 정의 recorder가 우선된다 (`@ConditionalOnMissingBean(LeaderAopMetricsRecorder::class)`).
     */
    @Bean
    @ConditionalOnBean(MeterRegistry::class)
    @ConditionalOnMissingBean(LeaderAopMetricsRecorder::class)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun micrometerLeaderAopMetricsRecorder(registry: MeterRegistry): MicrometerLeaderAopMetricsRecorder =
        MicrometerLeaderAopMetricsRecorder(registry)
}
