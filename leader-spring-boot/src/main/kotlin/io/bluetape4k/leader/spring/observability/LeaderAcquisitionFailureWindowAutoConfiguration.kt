package io.bluetape4k.leader.spring.observability

import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopAutoConfiguration
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Role

/** 최근 backend 획득 실패 aggregate recorder를 AOP recorder 목록보다 먼저 등록합니다. */
@AutoConfiguration(
    before = [LeaderAopAutoConfiguration::class],
)
@ConditionalOnClass(LeaderAopMetricsRecorder::class)
@ConditionalOnProperty(
    prefix = "bluetape4k.leader.observability",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(LeaderProperties::class)
class LeaderAcquisitionFailureWindowAutoConfiguration {

    @Bean("leaderAcquisitionFailureWindow")
    @ConditionalOnMissingBean(name = ["leaderAcquisitionFailureWindow"])
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    internal fun leaderAcquisitionFailureWindow(
        properties: LeaderProperties,
    ): LeaderAcquisitionFailureWindow = LeaderAcquisitionFailureWindow(
        window = properties.observability.health.acquisitionFailureWindow,
    )
}
