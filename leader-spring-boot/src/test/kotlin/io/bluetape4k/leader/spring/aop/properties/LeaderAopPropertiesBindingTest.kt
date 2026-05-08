package io.bluetape4k.leader.spring.aop.properties

import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.Duration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderAopPropertiesBindingTest {

    @Test
    fun `bluetape4k_leader_aop_ YAML 키가 LeaderAopProperties에 바인딩된다`() {
        val source = MapConfigurationPropertySource(
            mapOf(
                "bluetape4k.leader.aop.enabled" to "false",
                "bluetape4k.leader.aop.strict" to "true",
                "bluetape4k.leader.aop.failure-mode" to "SKIP",
                "bluetape4k.leader.aop.default-wait-time" to "PT7S",
                "bluetape4k.leader.aop.default-lease-time" to "PT3M",
                "bluetape4k.leader.aop.lock-name-prefix" to "myapp:",
                "bluetape4k.leader.aop.metrics.enabled" to "false",
                "bluetape4k.leader.aop.spel.allow-method-invocation" to "true",
            ),
        )
        val props = Binder(source).bind(LeaderAopProperties.PREFIX, LeaderAopProperties::class.java).get()

        props.enabled shouldBeEqualTo false
        props.strict shouldBeEqualTo true
        props.failureMode shouldBeEqualTo LeaderAspectFailureMode.SKIP
        props.defaultWaitTime shouldBeEqualTo Duration.ofSeconds(7)
        props.defaultLeaseTime shouldBeEqualTo Duration.ofMinutes(3)
        props.lockNamePrefix shouldBeEqualTo "myapp:"
        props.metrics.enabled shouldBeEqualTo false
        props.spel.allowMethodInvocation shouldBeEqualTo true
    }

    @Test
    fun `빈 source는 default 값으로 바인딩된다`() {
        val source = MapConfigurationPropertySource(emptyMap<String, String>())
        val props = Binder(source)
            .bindOrCreate(LeaderAopProperties.PREFIX, LeaderAopProperties::class.java)

        props.enabled shouldBeEqualTo true
        props.strict shouldBeEqualTo false
        props.failureMode shouldBeEqualTo LeaderAspectFailureMode.RETHROW
        props.defaultWaitTime shouldBeEqualTo LeaderAopProperties.DEFAULT_WAIT_TIME
        props.defaultLeaseTime shouldBeEqualTo LeaderAopProperties.DEFAULT_LEASE_TIME
        props.lockNamePrefix shouldBeEqualTo LeaderAopProperties.DEFAULT_LOCK_NAME_PREFIX
        props.metrics.enabled shouldBeEqualTo true
        props.spel.allowMethodInvocation shouldBeEqualTo false
    }

    @Test
    fun `metrics_enabled false 바인딩 — Micrometer 비활성화 시나리오`() {
        val source = MapConfigurationPropertySource(
            mapOf("bluetape4k.leader.aop.metrics.enabled" to "false"),
        )
        val props = Binder(source)
            .bindOrCreate(LeaderAopProperties.PREFIX, LeaderAopProperties::class.java)

        props.metrics.enabled shouldBeEqualTo false
    }
}
