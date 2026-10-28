package io.bluetape4k.leader.consul.contract

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.consul.ConsulLeaderElectionOptions
import io.bluetape4k.leader.consul.ConsulLeaderElector
import io.bluetape4k.leader.contract.AbstractLeaderElectorLeaderIdContractTest
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.junit.jupiter.api.TestInstance

/**
 * Consul blocking leader-id contract implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsulLeaderElectorLeaderIdContractTest: AbstractLeaderElectorLeaderIdContractTest() {

    companion object: KLogging()

    override fun createElector(options: LeaderElectionOptions): LeaderElector {
        log.info { "Create ConsulLeaderElector. options=$options" }

        return ConsulLeaderElector(
            ConsulContractSupport.endpoint(),
            ConsulLeaderElectionOptions(
                leaderOptions = options,
                keyPrefix = ConsulContractSupport.keyPrefix(),
            ),
        )
    }
}
