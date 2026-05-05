package io.bluetape4k.leader.spring.boot3.metrics

import io.bluetape4k.leader.micrometer.MicrometerLeaderAopMetricsRecorder
import io.bluetape4k.leader.spring.aop.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.spring.boot3.aop.autoconfigure.LeaderAopAutoConfiguration
import io.bluetape4k.leader.spring.boot3.aop.autoconfigure.LeaderAopFactoryAutoConfiguration
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
 * Spring Boot 3 Micrometer metrics AutoConfiguration.
 *
 * `MeterRegistry` 빈이 존재할 때 [MicrometerLeaderAopMetricsRecorder]를 자동 등록한다.
 * [LeaderAopAutoConfiguration] 보다 먼저 평가되어 Aspect 초기화 시 recorder가 주입된다.
 *
 * ## AutoConfig 순서
 * ```
 * LeaderAopFactoryAutoConfiguration (backend factories)
 *   ↓
 * LeaderMicrometerAutoConfiguration  ← 본 클래스
 *   ↓
 * LeaderAopAutoConfiguration (Aspect + BPP)
 * ```
 *
 * ## 비활성화
 * `bluetape4k.leader.aop.metrics.enabled=false` 설정으로 recorder 빈 등록 전체를 비활성화할 수 있다.
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
