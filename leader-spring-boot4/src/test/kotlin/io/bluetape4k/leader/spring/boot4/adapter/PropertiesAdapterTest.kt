package io.bluetape4k.leader.spring.boot4.adapter

import io.bluetape4k.leader.spring.boot4.Boot4LeaderProperties
import io.bluetape4k.leader.spring.properties.LeaderGroupProperties
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PropertiesAdapterTest {

    @Test
    fun `toCommonElection 가 properties로부터 LeaderElectionOptions 생성`() {
        val props = Boot4LeaderProperties(
            waitTime = Duration.ofSeconds(7),
            leaseTime = Duration.ofMinutes(1),
        )
        val options = PropertiesAdapter.toCommonElection(props)
        options.waitTime shouldBeEqualTo Duration.ofSeconds(7)
        options.leaseTime shouldBeEqualTo Duration.ofMinutes(1)
    }

    @Test
    fun `toCommonGroup 가 group 속성으로부터 옵션 생성`() {
        val props = Boot4LeaderProperties(
            group = LeaderGroupProperties(
                maxLeaders = 4,
                waitTime = Duration.ofSeconds(2),
                leaseTime = Duration.ofSeconds(30),
            ),
        )
        val options = PropertiesAdapter.toCommonGroup(props)
        options.maxLeaders shouldBeEqualTo 4
        options.waitTime shouldBeEqualTo Duration.ofSeconds(2)
        options.leaseTime shouldBeEqualTo Duration.ofSeconds(30)
    }

    @Test
    fun `default Boot4LeaderProperties 가 5초 wait, 60초 lease 변환`() {
        val options = PropertiesAdapter.toCommonElection(Boot4LeaderProperties())
        options.waitTime shouldBeEqualTo Duration.ofSeconds(5)
        options.leaseTime shouldBeEqualTo Duration.ofSeconds(60)
    }

    @Test
    fun `default group 옵션은 maxLeaders 2`() {
        val options = PropertiesAdapter.toCommonGroup(Boot4LeaderProperties())
        options.maxLeaders shouldBeEqualTo 2
    }
}
