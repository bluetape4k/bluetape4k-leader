package io.bluetape4k.leader

import io.bluetape4k.logging.KLogging
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderGroupElectionOptionsTest {

    companion object: KLogging()

    @Test
    fun `기본 옵션 값이 올바르다`() {
        val options = LeaderGroupElectionOptions.Default
        options.maxLeaders shouldBeEqualTo LeaderGroupElectionOptions.DefaultMaxLeaders
        options.waitTime shouldBeEqualTo LeaderGroupElectionOptions.DefaultWaitTime
        options.leaseTime shouldBeEqualTo LeaderGroupElectionOptions.DefaultLeaseTime
        options.minLeaseTime shouldBeEqualTo Duration.ZERO
    }

    @Test
    fun `커스텀 maxLeaders 를 설정할 수 있다`() {
        val options = LeaderGroupElectionOptions(maxLeaders = 5)
        options.maxLeaders shouldBeEqualTo 5
        options.waitTime shouldBeEqualTo LeaderGroupElectionOptions.DefaultWaitTime
        options.leaseTime shouldBeEqualTo LeaderGroupElectionOptions.DefaultLeaseTime
        options.minLeaseTime shouldBeEqualTo Duration.ZERO
    }

    @Test
    fun `커스텀 waitTime 과 leaseTime 을 설정할 수 있다`() {
        val options = LeaderGroupElectionOptions(
            maxLeaders = 4,
            waitTime = 10.seconds,
            leaseTime = 120.seconds,
            minLeaseTime = 5.seconds,
        )
        options.maxLeaders shouldBeEqualTo 4
        options.waitTime shouldBeEqualTo 10.seconds
        options.leaseTime shouldBeEqualTo 120.seconds
        options.minLeaseTime shouldBeEqualTo 5.seconds
    }

    @Test
    fun `data class copy 가 새로운 인스턴스를 반환한다`() {
        val original = LeaderGroupElectionOptions(maxLeaders = 3)
        val copied = original.copy(maxLeaders = 6)

        copied.maxLeaders shouldBeEqualTo 6
        copied.waitTime shouldBeEqualTo original.waitTime
        copied.leaseTime shouldBeEqualTo original.leaseTime
        original.maxLeaders shouldBeEqualTo 3  // 원본 불변
    }

    @Test
    fun `data class equality - 동일 값이면 동등하다`() {
        val options1 = LeaderGroupElectionOptions(maxLeaders = 3)
        val options2 = LeaderGroupElectionOptions(maxLeaders = 3)
        options1 shouldBeEqualTo options2
    }

    @Test
    fun `Default 인스턴스는 null 이 아니다`() {
        LeaderGroupElectionOptions.Default.shouldNotBeNull()
    }
}
