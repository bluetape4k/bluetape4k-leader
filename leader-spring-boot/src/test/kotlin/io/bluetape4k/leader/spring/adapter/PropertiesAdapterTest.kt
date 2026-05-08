package io.bluetape4k.leader.spring.adapter

import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.properties.LeaderGroupProperties
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PropertiesAdapterTest {

    @Test
    fun `toCommonElection 가 properties로부터 LeaderElectionOptions 생성`() {
        val props = LeaderProperties(
            waitTime = Duration.ofSeconds(7),
            leaseTime = Duration.ofMinutes(1),
        )
        val options = PropertiesAdapter.toCommonElection(props)
        options.waitTime shouldBeEqualTo 7.seconds
        options.leaseTime shouldBeEqualTo 1.minutes
    }

    @Test
    fun `toCommonGroup 가 group 속성으로부터 옵션 생성`() {
        val props = LeaderProperties(
            group = LeaderGroupProperties(
                maxLeaders = 4,
                waitTime = Duration.ofSeconds(2),
                leaseTime = Duration.ofSeconds(30),
            ),
        )
        val options = PropertiesAdapter.toCommonGroup(props)
        options.maxLeaders shouldBeEqualTo 4
        options.waitTime shouldBeEqualTo 2.seconds
        options.leaseTime shouldBeEqualTo 30.seconds
    }

    @Test
    fun `default LeaderProperties 가 5초 wait, 60초 lease 변환`() {
        val options = PropertiesAdapter.toCommonElection(LeaderProperties())
        options.waitTime shouldBeEqualTo 5.seconds
        options.leaseTime shouldBeEqualTo 60.seconds
    }

    @Test
    fun `default group 옵션은 maxLeaders 2`() {
        val options = PropertiesAdapter.toCommonGroup(LeaderProperties())
        options.maxLeaders shouldBeEqualTo 2
    }
}
