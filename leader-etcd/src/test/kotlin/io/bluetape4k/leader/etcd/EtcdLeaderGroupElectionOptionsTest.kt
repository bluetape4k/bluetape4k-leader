package io.bluetape4k.leader.etcd

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.LeaderGroupElectionOptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.milliseconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtcdLeaderGroupElectionOptionsTest {

    @Test
    fun `default options use shared group defaults and default key prefix`() {
        val options = EtcdLeaderGroupElectionOptions.Default

        options.leaderGroupOptions shouldBeEqualTo LeaderGroupElectionOptions.Default
        options.maxLeaders shouldBeEqualTo LeaderGroupElectionOptions.Default.maxLeaders
        options.keyPrefix shouldBeEqualTo "/bluetape4k/leader"
        options.retryDelay shouldBeEqualTo 50.milliseconds
    }

    @Test
    fun `custom group options are retained`() {
        val groupOptions = LeaderGroupElectionOptions(maxLeaders = 4)
        val options = EtcdLeaderGroupElectionOptions(
            leaderGroupOptions = groupOptions,
            keyPrefix = "/apps/orders/leader",
            retryDelay = 100.milliseconds,
        )

        options.leaderGroupOptions shouldBeEqualTo groupOptions
        options.maxLeaders shouldBeEqualTo 4
        options.keyPrefix shouldBeEqualTo "/apps/orders/leader"
        options.retryDelay shouldBeEqualTo 100.milliseconds
    }

    @Test
    fun `invalid prefix and retry delay are rejected`() {
        assertFailsWith<IllegalArgumentException> { EtcdLeaderGroupElectionOptions(keyPrefix = "relative") }
        assertFailsWith<IllegalArgumentException> { EtcdLeaderGroupElectionOptions(retryDelay = 0.milliseconds) }
    }
}
