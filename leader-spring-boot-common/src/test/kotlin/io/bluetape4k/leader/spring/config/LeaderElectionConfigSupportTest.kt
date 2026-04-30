package io.bluetape4k.leader.spring.config

import io.bluetape4k.leader.spring.properties.LeaderElectionProperties
import io.bluetape4k.leader.spring.properties.LeaderGroupProperties
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderElectionConfigSupportTest {

    private class TestSupport(private val props: LeaderElectionProperties) : LeaderElectionConfigSupport() {
        override fun properties(): LeaderElectionProperties = props
        fun exposedElectionOptions() = electionOptions()
        fun exposedGroupElectionOptions() = groupElectionOptions()
    }

    @Test
    fun `electionOptions 가 properties로 부터 LeaderElectionOptions 생성`() {
        val support = TestSupport(
            LeaderElectionProperties(
                waitTime = Duration.ofSeconds(7),
                leaseTime = Duration.ofMinutes(1),
            ),
        )
        val options = support.exposedElectionOptions()
        options.waitTime shouldBeEqualTo Duration.ofSeconds(7)
        options.leaseTime shouldBeEqualTo Duration.ofMinutes(1)
    }

    @Test
    fun `groupElectionOptions 가 group 속성으로부터 옵션 생성`() {
        val support = TestSupport(
            LeaderElectionProperties(
                group = LeaderGroupProperties(
                    maxLeaders = 4,
                    waitTime = Duration.ofSeconds(1),
                    leaseTime = Duration.ofSeconds(30),
                ),
            ),
        )
        val options = support.exposedGroupElectionOptions()
        options.maxLeaders shouldBeEqualTo 4
        options.waitTime shouldBeEqualTo Duration.ofSeconds(1)
        options.leaseTime shouldBeEqualTo Duration.ofSeconds(30)
    }
}
