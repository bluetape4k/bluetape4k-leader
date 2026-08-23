package io.bluetape4k.leader.spring.scheduling

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.Duration

class LeaderScheduledPolicyPropertiesTest {

    @Test
    fun `scheduling policy defaults and yaml keys bind`() {
        val source = MapConfigurationPropertySource(
            mapOf(
                "bluetape4k.leader.scheduling.enabled" to "true",
                "bluetape4k.leader.scheduling.policies[0].selector" to "orderJob#reconcile",
                "bluetape4k.leader.scheduling.policies[0].name" to "orders:reconcile",
                "bluetape4k.leader.scheduling.policies[0].wait-time" to "0s",
                "bluetape4k.leader.scheduling.policies[0].lease-time" to "30s",
                "bluetape4k.leader.scheduling.policies[0].min-lease-time" to "5s",
                "bluetape4k.leader.scheduling.policies[0].bean" to "redisLeaderElectionFactory",
                "bluetape4k.leader.scheduling.policies[0].auto-extend" to "false",
                "bluetape4k.leader.scheduling.policies[0].stream-bounded" to "false",
                "bluetape4k.leader.scheduling.policies[0].failure-mode" to "SKIP",
            ),
        )

        val props = Binder(source)
            .bindOrCreate(LeaderScheduledPolicyProperties.PREFIX, LeaderScheduledPolicyProperties::class.java)

        props.enabled.shouldBeTrue()
        props.policies.single().selector shouldBeEqualTo "orderJob#reconcile"
        props.policies.single().name shouldBeEqualTo "orders:reconcile"
        props.policies.single().waitTime shouldBeEqualTo Duration.ZERO
        props.policies.single().leaseTime shouldBeEqualTo Duration.ofSeconds(30)
        props.policies.single().minLeaseTime shouldBeEqualTo Duration.ofSeconds(5)
        props.policies.single().bean shouldBeEqualTo "redisLeaderElectionFactory"
        props.policies.single().autoExtend.shouldBeFalse()
        props.policies.single().streamBounded.shouldBeFalse()
        props.policies.single().failureMode shouldBeEqualTo LeaderAspectFailureMode.SKIP
    }

    @Test
    fun `empty source disables scheduling policy by default`() {
        val props = Binder(MapConfigurationPropertySource(emptyMap<String, String>()))
            .bindOrCreate(LeaderScheduledPolicyProperties.PREFIX, LeaderScheduledPolicyProperties::class.java)

        props.enabled.shouldBeFalse()
        props.policies.shouldBeEmpty()
    }

    @Test
    fun `invalid duration text is rejected by Spring Binder`() {
        val source = MapConfigurationPropertySource(
            mapOf("bluetape4k.leader.scheduling.policies[0].lease-time" to "not-a-duration"),
        )

        assertFailsWith<Exception> {
            Binder(source).bindOrCreate(
                LeaderScheduledPolicyProperties.PREFIX,
                LeaderScheduledPolicyProperties::class.java,
            )
        }
    }
}
