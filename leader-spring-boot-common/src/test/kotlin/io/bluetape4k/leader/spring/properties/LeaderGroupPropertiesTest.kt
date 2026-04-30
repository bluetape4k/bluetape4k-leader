package io.bluetape4k.leader.spring.properties

import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderGroupPropertiesTest {

    @Test
    fun `기본값으로 LeaderGroupElectionOptions 생성`() {
        val props = LeaderGroupProperties()
        val options = props.toOptions()

        options.maxLeaders shouldBeEqualTo 2
        options.waitTime shouldBeEqualTo Duration.ofSeconds(5)
        options.leaseTime shouldBeEqualTo Duration.ofSeconds(60)
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
