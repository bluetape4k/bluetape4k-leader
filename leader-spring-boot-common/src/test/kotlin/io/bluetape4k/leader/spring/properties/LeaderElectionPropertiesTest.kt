package io.bluetape4k.leader.spring.properties

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Duration

class LeaderElectionPropertiesTest {

    companion object: KLogging()

    @Test
    fun `기본값으로 LeaderElectionOptions 생성`() {
        val props = LeaderElectionProperties()
        val options = props.toOptions()

        options.waitTime shouldBeEqualTo LeaderElectionOptions.DefaultWaitTime
        options.leaseTime shouldBeEqualTo LeaderElectionOptions.DefaultLeaseTime
    }

    @Test
    fun `사용자 지정 값 매핑`() {
        val props = LeaderElectionProperties(
            waitTime = Duration.ofSeconds(3),
            leaseTime = Duration.ofSeconds(120),
        )
        val options = props.toOptions()

        options.waitTime shouldBeEqualTo Duration.ofSeconds(3)
        options.leaseTime shouldBeEqualTo Duration.ofSeconds(120)
    }

    @Test
    fun `group 속성 기본값`() {
        val props = LeaderElectionProperties()
        props.group.maxLeaders shouldBeEqualTo LeaderGroupProperties.DefaultMaxLeaders
        props.group.waitTime shouldBeEqualTo LeaderGroupProperties.DefaultWaitTime
        props.group.leaseTime shouldBeEqualTo LeaderGroupProperties.DefaultLeaseTime
    }
}
