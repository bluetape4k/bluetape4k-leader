package io.bluetape4k.leader.spring.observability

import io.bluetape4k.leader.spring.route.LeaderRouteLeaseRuntime
import io.bluetape4k.leader.spring.route.LeaderRouteGuardAutoConfiguration
import io.bluetape4k.leader.spring.route.LeaderRouteLeaseDiagnosticsContributor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/** LEASE route가 활성화된 context에만 bounded aggregate Actuator endpoint를 추가합니다. */
@AutoConfiguration(after = [LeaderRouteGuardAutoConfiguration::class])
@ConditionalOnClass(name = ["org.springframework.boot.actuate.endpoint.annotation.Endpoint"])
@ConditionalOnBean(LeaderRouteLeaseRuntime::class)
@ConditionalOnProperty(
    prefix = "management.endpoint.leaderRouteLease",
    name = ["enabled"],
    havingValue = "true",
)
class LeaderRouteLeaseActuatorAutoConfiguration {

    @Bean("leaderRouteLeaseDiagnosticsContributor")
    @ConditionalOnMissingBean(LeaderRouteLeaseDiagnosticsContributor::class)
    internal fun leaderRouteLeaseDiagnosticsContributor(
        runtime: LeaderRouteLeaseRuntime,
    ): LeaderRouteLeaseDiagnosticsContributor = LeaderRouteLeaseDiagnosticsContributor(runtime)
}
