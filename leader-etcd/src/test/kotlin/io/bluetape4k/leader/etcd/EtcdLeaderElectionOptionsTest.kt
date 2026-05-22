package io.bluetape4k.leader.etcd

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.LeaderElectionOptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtcdLeaderElectionOptionsTest {

    @Test
    fun `default options use shared leader defaults and default key prefix`() {
        val options = EtcdLeaderElectionOptions.Default

        options.leaderOptions shouldBeEqualTo LeaderElectionOptions.Default
        options.keyPrefix shouldBeEqualTo "/bluetape4k/leader"
        options.retryDelay shouldBeEqualTo 50.milliseconds
    }

    @Test
    fun `custom leader options are retained`() {
        val leaderOptions = LeaderElectionOptions(leaseTime = 30.seconds, autoExtend = true)
        val options = EtcdLeaderElectionOptions(
            leaderOptions = leaderOptions,
            keyPrefix = "/apps/orders/leader",
            retryDelay = 100.milliseconds,
        )

        options.leaderOptions shouldBeEqualTo leaderOptions
        options.keyPrefix shouldBeEqualTo "/apps/orders/leader"
        options.retryDelay shouldBeEqualTo 100.milliseconds
    }

    @Test
    fun `invalid prefix and retry delay are rejected`() {
        assertFailsWith<IllegalArgumentException> { EtcdLeaderElectionOptions(keyPrefix = "relative") }
        assertFailsWith<IllegalArgumentException> { EtcdLeaderElectionOptions(retryDelay = 0.milliseconds) }
    }
}
