package io.bluetape4k.leader.spring.compatibility

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.aop.LeaderBeanSelector
import io.bluetape4k.leader.spring.aop.LeaderElectionAspect
import io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopAutoConfiguration
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.leader.spring.aop.util.LockNameValidator
import io.bluetape4k.leader.spring.observability.LeaderElectionActuatorAutoConfiguration
import io.bluetape4k.leader.spring.observability.LeaderElectionReadinessHealthAutoConfiguration
import io.bluetape4k.leader.spring.observability.LeaderElectionReadinessHealthIndicator
import io.bluetape4k.leader.spring.observability.LeaderElectionStatusRegistry
import io.bluetape4k.leader.spring.observability.LeaderElectionStatusResponse
import io.bluetape4k.leader.spring.observability.LeaderElectionStatusEndpoint
import io.bluetape4k.leader.spring.properties.LeaderObservabilityHealthProperties
import io.bluetape4k.leader.spring.route.LeaderRouteGuardConfigurationException
import java.io.ObjectStreamClass
import java.time.Clock
import java.time.Duration
import kotlin.jvm.internal.DefaultConstructorMarker
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.health.contributor.HealthIndicator

class PublicJvmAbiCompatibilityTest {

    @Test
    fun `0_5_0에 공개된 Spring auto configuration 메서드 descriptor를 유지한다`() {
        LeaderAopAutoConfiguration::class.java.getMethod(
            "leaderElectionAspect",
            LeaderBeanSelector::class.java,
            LeaderAopProperties::class.java,
            SpelExpressionEvaluator::class.java,
            LockNameValidator::class.java,
            ObjectProvider::class.java,
        ).returnType shouldBeEqualTo LeaderElectionAspect::class.java

        LeaderElectionActuatorAutoConfiguration::class.java.getMethod(
            "leaderElectionStatusEndpoint",
            ConfigurableListableBeanFactory::class.java,
            LeaderProperties::class.java,
            LeaderElectionStatusRegistry::class.java,
        ).returnType shouldBeEqualTo LeaderElectionStatusEndpoint::class.java

        LeaderElectionReadinessHealthAutoConfiguration::class.java.getMethod(
            "leaderElectionReadiness",
            ConfigurableListableBeanFactory::class.java,
            LeaderElectionStatusRegistry::class.java,
            LeaderProperties::class.java,
        ).returnType shouldBeEqualTo HealthIndicator::class.java

        LeaderElectionReadinessHealthIndicator.Companion::class.java.getMethod(
            "fromSelectedState\$io_github_bluetape4k_leader_bluetape4k_leader_spring_boot",
            String::class.java,
            String::class.java,
            io.bluetape4k.leader.LeaderElectionState::class.java,
            LeaderElectionStatusRegistry::class.java,
            Duration::class.java,
            Clock::class.java,
        ).returnType shouldBeEqualTo LeaderElectionReadinessHealthIndicator::class.java

        LeaderElectionStatusEndpoint.Companion::class.java.getMethod(
            "fromSelectedState\$io_github_bluetape4k_leader_bluetape4k_leader_spring_boot",
            String::class.java,
            String::class.java,
            io.bluetape4k.leader.LeaderElectionState::class.java,
            LeaderElectionStatusRegistry::class.java,
        ).returnType shouldBeEqualTo LeaderElectionStatusEndpoint::class.java
    }

    @Test
    fun `0_5_0 data class synthetic constructor descriptor를 유지한다`() {
        val statusConstructor = LeaderElectionStatusResponse::class.java.getConstructor(
            List::class.java,
            String::class.java,
            String::class.java,
            booleanType,
            intType,
            DefaultConstructorMarker::class.java,
        )
        statusConstructor.isSynthetic shouldBeEqualTo true
        val status = statusConstructor.newInstance(emptyList<Any>(), null, null, true, 0b1110, null)
            as LeaderElectionStatusResponse
        status.backend shouldBeEqualTo "unknown"
        status.stateProviderBean shouldBeEqualTo ""
        status.stateSupported shouldBeEqualTo false

        val healthConstructor = LeaderObservabilityHealthProperties::class.java.getConstructor(
            booleanType,
            Duration::class.java,
            intType,
            DefaultConstructorMarker::class.java,
        )
        healthConstructor.isSynthetic shouldBeEqualTo true
        val health = healthConstructor.newInstance(true, null, 0b11, null)
            as LeaderObservabilityHealthProperties
        health.enabled shouldBeEqualTo false
        health.leaseWarningThreshold shouldBeEqualTo Duration.ofSeconds(10)
    }

    @Test
    fun `route guard 예외의 기존 serialVersionUID를 유지한다`() {
        ObjectStreamClass.lookup(LeaderRouteGuardConfigurationException::class.java)
            .serialVersionUID shouldBeEqualTo LEGACY_ROUTE_GUARD_SERIAL_VERSION_UID
    }

    companion object {
        private val booleanType = Boolean::class.javaPrimitiveType ?: error("missing boolean type")
        private val intType = Int::class.javaPrimitiveType ?: error("missing int type")
        private const val LEGACY_ROUTE_GUARD_SERIAL_VERSION_UID = 2956475360686774009L
    }
}
