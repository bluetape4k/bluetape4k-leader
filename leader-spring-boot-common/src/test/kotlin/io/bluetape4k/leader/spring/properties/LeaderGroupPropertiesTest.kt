package io.bluetape4k.leader.spring.properties

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Duration

class LeaderGroupPropertiesTest {

    companion object: KLogging()

    @Test
    fun `기본값으로 LeaderGroupElectionOptions 생성`() {
        val props = LeaderGroupProperties()
        val options = props.toOptions()

        options.maxLeaders shouldBeEqualTo LeaderGroupElectionOptions.DefaultMaxLeaders
        options.waitTime shouldBeEqualTo LeaderGroupElectionOptions.DefaultWaitTime
        options.leaseTime shouldBeEqualTo LeaderGroupElectionOptions.DefaultLeaseTime
    }

    @Test
    fun `사용자 지정 값 매핑`() {
        val props = LeaderGroupProperties(
            maxLeaders = 5,
            waitTime = Duration.ofSeconds(2),
            leaseTime = Duration.ofMinutes(2),
        )
        val options = props.toOptions()

        options.maxLeaders shouldBeEqualTo 5
        options.waitTime shouldBeEqualTo Duration.ofSeconds(2)
        options.leaseTime shouldBeEqualTo Duration.ofMinutes(2)
    }
}
