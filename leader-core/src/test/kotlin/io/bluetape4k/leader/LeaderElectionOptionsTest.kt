package io.bluetape4k.leader

import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LeaderElectionOptionsTest {

    companion object: KLogging()

    @Test
    fun `기본 옵션 값이 올바르다`() {
        val options = LeaderElectionOptions.Default
        options.waitTime shouldBeEqualTo LeaderElectionOptions.DefaultWaitTime
        options.leaseTime shouldBeEqualTo LeaderElectionOptions.DefaultLeaseTime
    }

    @Test
    fun `커스텀 옵션 값을 설정할 수 있다`() {
        val options = LeaderElectionOptions(
            waitTime = 10.seconds,
            leaseTime = 120.seconds,
        )
        options.waitTime shouldBeEqualTo 10.seconds
        options.leaseTime shouldBeEqualTo 120.seconds
    }

    @Test
    fun `LeaderGroupElectionOptions 기본 값이 올바르다`() {
        val options = LeaderGroupElectionOptions.Default
        options.maxLeaders shouldBeEqualTo LeaderGroupElectionOptions.DefaultMaxLeaders
        options.waitTime shouldBeEqualTo LeaderGroupElectionOptions.DefaultWaitTime
        options.leaseTime shouldBeEqualTo LeaderElectionOptions.DefaultLeaseTime
    }

    @Test
    fun `LeaderGroupElectionOptions maxLeaders 가 0 이하면 예외가 발생한다`() {
        assertThrows<IllegalArgumentException> {
            LeaderGroupElectionOptions(maxLeaders = 0)
        }
        assertThrows<IllegalArgumentException> {
            LeaderGroupElectionOptions(maxLeaders = -1)
        }
    }

    @Test
    fun `LeaderElectionOptions leaseTime 이 0 이하면 예외가 발생한다`() {
        assertThrows<IllegalArgumentException> {
            LeaderElectionOptions(leaseTime = Duration.ZERO)
        }
        assertThrows<IllegalArgumentException> {
            LeaderElectionOptions(leaseTime = -1.seconds)
        }
    }

    @Test
    fun `LeaderElectionOptions waitTime 이 음수면 예외가 발생한다`() {
        assertThrows<IllegalArgumentException> {
            LeaderElectionOptions(waitTime = -1.milliseconds)
        }
    }

    @Test
    fun `LeaderGroupElectionOptions leaseTime 이 0 이하면 예외가 발생한다`() {
        assertThrows<IllegalArgumentException> {
            LeaderGroupElectionOptions(leaseTime = Duration.ZERO)
        }
    }

    @Test
    fun `blank lockName 으로 호출 시 예외가 발생한다`() {
        val election = io.bluetape4k.leader.local.LocalLeaderElector()
        assertThrows<IllegalArgumentException> {
            election.runIfLeader("") { "should fail" }
        }
        assertThrows<IllegalArgumentException> {
            election.runIfLeader("   ") { "should fail" }
        }
    }
}
