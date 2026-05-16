package io.bluetape4k.leader

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderElectionOptionsTest {

    companion object: KLogging()

    @Test
    fun `기본 옵션 값이 올바르다`() {
        val options = LeaderElectionOptions.Default
        options.waitTime shouldBeEqualTo LeaderElectionOptions.DefaultWaitTime
        options.leaseTime shouldBeEqualTo LeaderElectionOptions.DefaultLeaseTime
        options.minLeaseTime shouldBeEqualTo Duration.ZERO
        options.autoExtend shouldBeEqualTo false
    }

    @Test
    fun `커스텀 옵션 값을 설정할 수 있다`() {
        val options = LeaderElectionOptions(
            waitTime = 10.seconds,
            leaseTime = 120.seconds,
            minLeaseTime = 5.seconds,
            autoExtend = true,
        )
        options.waitTime shouldBeEqualTo 10.seconds
        options.leaseTime shouldBeEqualTo 120.seconds
        options.minLeaseTime shouldBeEqualTo 5.seconds
        options.autoExtend shouldBeEqualTo true
    }

    @Test
    fun `LeaderGroupElectionOptions 기본 값이 올바르다`() {
        val options = LeaderGroupElectionOptions.Default
        options.maxLeaders shouldBeEqualTo LeaderGroupElectionOptions.DefaultMaxLeaders
        options.waitTime shouldBeEqualTo LeaderGroupElectionOptions.DefaultWaitTime
        options.leaseTime shouldBeEqualTo LeaderElectionOptions.DefaultLeaseTime
        options.minLeaseTime shouldBeEqualTo Duration.ZERO
    }

    @Test
    fun `LeaderGroupElectionOptions maxLeaders 가 0 이하면 예외가 발생한다`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderGroupElectionOptions(maxLeaders = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderGroupElectionOptions(maxLeaders = -1)
        }
    }

    @Test
    fun `LeaderElectionOptions leaseTime 이 0 이하면 예외가 발생한다`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderElectionOptions(leaseTime = Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderElectionOptions(leaseTime = -1.seconds)
        }
    }

    @Test
    fun `LeaderElectionOptions waitTime 이 음수면 예외가 발생한다`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderElectionOptions(waitTime = -1.milliseconds)
        }
    }

    @Test
    fun `LeaderElectionOptions minLeaseTime 이 음수거나 leaseTime 보다 크면 예외가 발생한다`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderElectionOptions(minLeaseTime = -1.milliseconds)
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderElectionOptions(leaseTime = 1.seconds, minLeaseTime = 2.seconds)
        }
    }

    @Test
    fun `LeaderGroupElectionOptions leaseTime 이 0 이하면 예외가 발생한다`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderGroupElectionOptions(leaseTime = Duration.ZERO)
        }
    }

    @Test
    fun `LeaderGroupElectionOptions minLeaseTime 이 음수거나 leaseTime 보다 크면 예외가 발생한다`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderGroupElectionOptions(minLeaseTime = -1.milliseconds)
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderGroupElectionOptions(leaseTime = 1.seconds, minLeaseTime = 2.seconds)
        }
    }

    @Test
    fun `blank lockName 으로 호출 시 예외가 발생한다`() {
        val election = io.bluetape4k.leader.local.LocalLeaderElector()
        assertFailsWith<IllegalArgumentException> {
            election.runIfLeader("") { "should fail" }
        }
        assertFailsWith<IllegalArgumentException> {
            election.runIfLeader("   ") { "should fail" }
        }
    }
}
