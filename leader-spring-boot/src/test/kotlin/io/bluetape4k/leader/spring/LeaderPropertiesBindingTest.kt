package io.bluetape4k.leader.spring

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.spring.properties.LeaderElectionProperties
import io.bluetape4k.leader.spring.properties.LeaderGroupProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.BindResult
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySource
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

private inline fun <reified T : Any> Binder.bindAs(name: String): BindResult<T> =
    bind(name, T::class.java)

private inline fun <reified T : Any> Binder.bindOrCreate(name: String): T =
    bindOrCreate(name, T::class.java)

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderPropertiesBindingTest {

    @Test
    fun `yaml 형식 키를 LeaderProperties에 바인딩`() {
        val source = MapConfigurationPropertySource(
            mapOf(
                "bluetape4k.leader.wait-time" to "7s",
                "bluetape4k.leader.lease-time" to "120s",
                "bluetape4k.leader.group.max-leaders" to "5",
                "bluetape4k.leader.group.wait-time" to "3s",
                "bluetape4k.leader.group.lease-time" to "45s",
                "bluetape4k.leader.mongo.single-collection" to "single_election",
                "bluetape4k.leader.mongo.group-collection" to "group_election",
                "bluetape4k.leader.etcd.key-prefix" to "/apps/orders/leader",
            ),
        )
        val props = Binder(source).bindAs<LeaderProperties>("bluetape4k.leader").get()

        props.waitTime shouldBeEqualTo Duration.ofSeconds(7)
        props.leaseTime shouldBeEqualTo Duration.ofSeconds(120)
        props.group.maxLeaders shouldBeEqualTo 5
        props.group.waitTime shouldBeEqualTo Duration.ofSeconds(3)
        props.group.leaseTime shouldBeEqualTo Duration.ofSeconds(45)
        props.mongo.singleCollection shouldBeEqualTo "single_election"
        props.mongo.groupCollection shouldBeEqualTo "group_election"
        props.etcd.keyPrefix shouldBeEqualTo "/apps/orders/leader"
    }

    @Test
    fun `빈 source는 default 값으로 바인딩`() {
        val source: ConfigurationPropertySource = MapConfigurationPropertySource(emptyMap<String, String>())
        val props = Binder(source).bindOrCreate<LeaderProperties>("bluetape4k.leader")

        props.waitTime shouldBeEqualTo Duration.ofSeconds(5)
        props.leaseTime shouldBeEqualTo Duration.ofSeconds(60)
        props.group.maxLeaders shouldBeEqualTo 2
        props.mongo.singleCollection shouldBeEqualTo "leader_election"
        props.mongo.groupCollection shouldBeEqualTo "leader_group_election"
        props.etcd.keyPrefix shouldBeEqualTo "/bluetape4k/leader"
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
        val props = Binder(source).bindAs<LeaderProperties>("bluetape4k.leader").get()
        props.waitTime shouldBeEqualTo Duration.ofSeconds(10)
        props.leaseTime shouldBeEqualTo Duration.ofSeconds(300)
        props.group.maxLeaders shouldBeEqualTo 8
    }

    // ── LeaderElectionProperties.toOptions() ──────────────────────────────

    @Test
    fun `LeaderElectionProperties toOptions - default 값이 LeaderElectionOptions 로 변환`() {
        val props = LeaderElectionProperties()
        val opts = props.toOptions()

        opts.waitTime shouldBeEqualTo 5.seconds
        opts.leaseTime shouldBeEqualTo 60.seconds
    }

    @Test
    fun `LeaderElectionProperties toOptions - 커스텀 값이 LeaderElectionOptions 로 변환`() {
        val props = LeaderElectionProperties(
            waitTime = Duration.ofSeconds(10),
            leaseTime = Duration.ofMinutes(2),
        )
        val opts = props.toOptions()

        opts.waitTime shouldBeEqualTo 10.seconds
        opts.leaseTime shouldBeEqualTo 120.seconds
    }

    // ── LeaderGroupProperties.toOptions() ─────────────────────────────────

    @Test
    fun `LeaderGroupProperties toOptions - default 값이 LeaderGroupElectionOptions 로 변환`() {
        val props = LeaderGroupProperties()
        val opts = props.toOptions()

        opts.maxLeaders shouldBeEqualTo 2
        opts.waitTime shouldBeEqualTo 5.seconds
        opts.leaseTime shouldBeEqualTo 60.seconds
    }

    @Test
    fun `LeaderGroupProperties toOptions - 커스텀 값이 LeaderGroupElectionOptions 로 변환`() {
        val props = LeaderGroupProperties(
            maxLeaders = 5,
            waitTime = Duration.ofSeconds(3),
            leaseTime = Duration.ofMinutes(1),
        )
        val opts = props.toOptions()

        opts.maxLeaders shouldBeEqualTo 5
        opts.waitTime shouldBeEqualTo 3.seconds
        opts.leaseTime shouldBeEqualTo 60.seconds
    }

    @EnableConfigurationProperties(LeaderProperties::class)
    private class TestConfig
}
