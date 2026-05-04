package io.bluetape4k.leader.spring.boot3

import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.BindResult
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySource
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.Duration

private inline fun <reified T : Any> Binder.bindAs(name: String): BindResult<T> =
    bind(name, T::class.java)

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Boot3LeaderPropertiesBindingTest {

    @Test
    fun `yaml 형식 키를 Boot3LeaderProperties에 바인딩`() {
        val source = MapConfigurationPropertySource(
            mapOf(
                "bluetape4k.leader.wait-time" to "7s",
                "bluetape4k.leader.lease-time" to "120s",
                "bluetape4k.leader.group.max-leaders" to "5",
                "bluetape4k.leader.group.wait-time" to "3s",
                "bluetape4k.leader.group.lease-time" to "45s",
                "bluetape4k.leader.mongo.single-collection" to "single_election",
                "bluetape4k.leader.mongo.group-collection" to "group_election",
            ),
        )
        val props = Binder(source).bindAs<Boot3LeaderProperties>("bluetape4k.leader").get()

        props.waitTime shouldBeEqualTo Duration.ofSeconds(7)
        props.leaseTime shouldBeEqualTo Duration.ofSeconds(120)
        props.group.maxLeaders shouldBeEqualTo 5
        props.group.waitTime shouldBeEqualTo Duration.ofSeconds(3)
        props.group.leaseTime shouldBeEqualTo Duration.ofSeconds(45)
        props.mongo.singleCollection shouldBeEqualTo "single_election"
        props.mongo.groupCollection shouldBeEqualTo "group_election"
    }

    @Test
    fun `빈 source는 default 값으로 바인딩`() {
        val source: ConfigurationPropertySource = MapConfigurationPropertySource(emptyMap<String, String>())
        val props = Binder(source).bindAs<Boot3LeaderProperties>("bluetape4k.leader")
            .orElse(Boot3LeaderProperties())

        props.waitTime shouldBeEqualTo Duration.ofSeconds(5)
        props.leaseTime shouldBeEqualTo Duration.ofSeconds(60)
        props.group.maxLeaders shouldBeEqualTo 2
        props.mongo.singleCollection shouldBeEqualTo "leader_election"
        props.mongo.groupCollection shouldBeEqualTo "leader_group_election"
    }

    @Test
    fun `여러 키가 동시 바인딩될 때 group과 단일 옵션 모두 적용`() {
        val source = MapConfigurationPropertySource(
            mapOf(
                "bluetape4k.leader.wait-time" to "10s",
                "bluetape4k.leader.lease-time" to "300s",
                "bluetape4k.leader.group.max-leaders" to "8",
            ),
        )
        val props = Binder(source).bindAs<Boot3LeaderProperties>("bluetape4k.leader").get()
        props.waitTime shouldBeEqualTo Duration.ofSeconds(10)
        props.leaseTime shouldBeEqualTo Duration.ofSeconds(300)
        props.group.maxLeaders shouldBeEqualTo 8
    }

    @EnableConfigurationProperties(Boot3LeaderProperties::class)
    private class TestConfig
}
