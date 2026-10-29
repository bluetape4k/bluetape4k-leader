package io.bluetape4k.leader.spring.diagnostics

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.spring.LeaderElectionAutoConfiguration
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.observability.LeaderElectionActuatorAutoConfiguration
import io.bluetape4k.logging.KLogging
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

/** diagnostics가 활성화되고 `LeaderElector`가 있을 때 startup inspection bean을 등록합니다. */
@AutoConfiguration(
    after = [
        LeaderElectionAutoConfiguration::class,
        LeaderElectionActuatorAutoConfiguration::class,
    ],
)
@ConditionalOnClass(LeaderElector::class)
@ConditionalOnProperty(
    prefix = "bluetape4k.leader.diagnostics",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(LeaderProperties::class, LeaderAopProperties::class)
class LeaderStartupDiagnosticsAutoConfiguration {

    companion object: KLogging()

    @Bean
    @ConditionalOnMissingBean
    fun leaderStartupDiagnostics(
        beanFactory: ConfigurableListableBeanFactory,
        environment: Environment,
        leaderProperties: LeaderProperties,
        aopProperties: LeaderAopProperties,
    ): LeaderStartupDiagnostics =
        LeaderStartupDiagnostics(beanFactory, environment, leaderProperties, aopProperties)
}
