package io.bluetape4k.leader.consul

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.consul.internal.ConsulLeaderPaths
import io.bluetape4k.leader.consul.internal.ConsulSessionTtl
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsulLeaderElectionOptionsTest {

    @Test
    fun `default options follow Consul TTL contract`() {
        val options = ConsulLeaderElectionOptions.Default

        options.keyPrefix shouldBeEqualTo "bluetape4k/leader"
        options.sessionNamePrefix shouldBeEqualTo "bluetape4k-leader"
        options.lockDelay shouldBeEqualTo Duration.ZERO
        ConsulSessionTtl.ttlSeconds(options.leaderOptions.leaseTime) shouldBeEqualTo 60L
    }

    @Test
    fun `validates lease time floor and ceiling`() {
        assertFailsWith<IllegalArgumentException> {
            ConsulLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(leaseTime = 9.seconds),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ConsulLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(leaseTime = 86_401.seconds),
            )
        }
    }

    @Test
    fun `validates key prefix and session name`() {
        assertFailsWith<IllegalArgumentException> {
            ConsulLeaderElectionOptions(keyPrefix = "/bluetape4k/leader")
        }
        assertFailsWith<IllegalArgumentException> {
            ConsulLeaderElectionOptions(keyPrefix = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            ConsulLeaderElectionOptions(sessionNamePrefix = " ")
        }
    }

    @Test
    fun `builds encoded single leader key`() {
        val paths = ConsulLeaderPaths("apps/orders/leader/")

        paths.single("daily:report_job-1") shouldBeEqualTo "apps/orders/leader/single/daily%3Areport_job-1"
    }

    @Test
    fun `computes renew delay before ttl expiry`() {
        ConsulSessionTtl.renewDelay(30.seconds) shouldBeEqualTo 10.seconds

        val minimumDelay = ConsulSessionTtl.renewDelay(10.seconds)
        minimumDelay shouldBeGreaterOrEqualTo 1.seconds
        (minimumDelay < 10.seconds).shouldBeTrue()
        minimumDelay shouldBeLessOrEqualTo (10.seconds / 3)
    }
}
