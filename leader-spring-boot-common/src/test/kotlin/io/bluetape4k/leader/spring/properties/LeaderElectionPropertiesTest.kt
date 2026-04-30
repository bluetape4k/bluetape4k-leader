package io.bluetape4k.leader.spring.properties

import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderElectionPropertiesTest {

    @Test
    fun `기본값으로 LeaderElectionOptions 생성`() {
        val props = LeaderElectionProperties()
        val options = props.toOptions()

        options.waitTime shouldBeEqualTo Duration.ofSeconds(5)
        options.leaseTime shouldBeEqualTo Duration.ofSeconds(60)
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
        props.group.maxLeaders shouldBeEqualTo 2
        props.group.waitTime shouldBeEqualTo Duration.ofSeconds(5)
        props.group.leaseTime shouldBeEqualTo Duration.ofSeconds(60)
    }
}
