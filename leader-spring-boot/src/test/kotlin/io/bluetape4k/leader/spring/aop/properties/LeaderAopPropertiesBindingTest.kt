package io.bluetape4k.leader.spring.aop.properties

import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.Duration
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue

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
                "bluetape4k.leader.aop.metrics.tags.lock-name.mode" to "HASH",
                "bluetape4k.leader.aop.metrics.tags.lock-name.hash-length" to "12",
                "bluetape4k.leader.aop.metrics.tags.lock-name.allow-list[0]" to "static-job",
                "bluetape4k.leader.aop.metrics.tags.lock-name.deny-list[0]" to "blocked-job",
                "bluetape4k.leader.aop.metrics.tags.lock-name.redacted-value" to "job",
                "bluetape4k.leader.aop.metrics.tags.leader-id.redacted-value" to "leader",
                "bluetape4k.leader.aop.metrics.tags.backend-name.mode" to "RAW",
                "bluetape4k.leader.aop.spel.allow-method-invocation" to "true",
            ),
        )
        val props = Binder(source).bind(LeaderAopProperties.PREFIX, LeaderAopProperties::class.java).get()

        props.enabled.shouldBeFalse()

        props.strict.shouldBeTrue()

        props.failureMode shouldBeEqualTo LeaderAspectFailureMode.SKIP
        props.defaultWaitTime shouldBeEqualTo Duration.ofSeconds(7)
        props.defaultLeaseTime shouldBeEqualTo Duration.ofMinutes(3)
        props.lockNamePrefix shouldBeEqualTo "myapp:"
        props.metrics.enabled.shouldBeFalse()

        props.metrics.tags.lockName.mode shouldBeEqualTo LeaderAopProperties.Metrics.TagMode.HASH
        props.metrics.tags.lockName.hashLength shouldBeEqualTo 12
        props.metrics.tags.lockName.allowList shouldBeEqualTo setOf("static-job")
        props.metrics.tags.lockName.denyList shouldBeEqualTo setOf("blocked-job")
        props.metrics.tags.lockName.redactedValue shouldBeEqualTo "job"
        props.metrics.tags.leaderId.redactedValue shouldBeEqualTo "leader"
        props.metrics.tags.backendName.mode shouldBeEqualTo LeaderAopProperties.Metrics.TagMode.RAW
        props.spel.allowMethodInvocation.shouldBeTrue()

    }

    @Test
    fun `빈 source는 default 값으로 바인딩된다`() {
        val source = MapConfigurationPropertySource(emptyMap<String, String>())
        val props = Binder(source)
            .bindOrCreate(LeaderAopProperties.PREFIX, LeaderAopProperties::class.java)

        props.enabled.shouldBeTrue()

        props.strict.shouldBeFalse()

        props.failureMode shouldBeEqualTo LeaderAspectFailureMode.RETHROW
        props.defaultWaitTime shouldBeEqualTo LeaderAopProperties.DEFAULT_WAIT_TIME
        props.defaultLeaseTime shouldBeEqualTo LeaderAopProperties.DEFAULT_LEASE_TIME
        props.lockNamePrefix shouldBeEqualTo LeaderAopProperties.DEFAULT_LOCK_NAME_PREFIX
        props.metrics.enabled.shouldBeTrue()

        props.metrics.tags.lockName.redactedValue shouldBeEqualTo "redacted-lock"
        props.metrics.tags.leaderId.redactedValue shouldBeEqualTo "redacted-leader"
        props.metrics.tags.backendName.mode shouldBeEqualTo LeaderAopProperties.Metrics.TagMode.RAW
        props.spel.allowMethodInvocation.shouldBeFalse()

    }

    @Test
    fun `metrics_enabled false 바인딩 — Micrometer 비활성화 시나리오`() {
        val source = MapConfigurationPropertySource(
            mapOf("bluetape4k.leader.aop.metrics.enabled" to "false"),
        )
        val props = Binder(source)
            .bindOrCreate(LeaderAopProperties.PREFIX, LeaderAopProperties::class.java)

        props.metrics.enabled.shouldBeFalse()

    }

    @Test
    fun `truncate mode requires positive max length`() {
        val source = MapConfigurationPropertySource(
            mapOf(
                "bluetape4k.leader.aop.metrics.tags.lock-name.mode" to "TRUNCATE",
                "bluetape4k.leader.aop.metrics.tags.lock-name.max-length" to "0",
            ),
        )

        assertFailsWith<Exception> {
            Binder(source).bindOrCreate(LeaderAopProperties.PREFIX, LeaderAopProperties::class.java)
        }
    }
}
