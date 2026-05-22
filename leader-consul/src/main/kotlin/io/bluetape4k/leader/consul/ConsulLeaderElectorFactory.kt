package io.bluetape4k.leader.consul

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory

/**
 * Factory for Consul single-leader electors.
 *
 * The supplied [ConsulEndpoint] is caller-owned and is never closed by created electors.
 */
class ConsulLeaderElectorFactory(
    private val endpoint: ConsulEndpoint,
    private val baseOptions: ConsulLeaderElectionOptions = ConsulLeaderElectionOptions.Default,
) : LeaderElectorFactory {

    override fun create(options: LeaderElectionOptions): LeaderElector =
        ConsulLeaderElector(
            endpoint,
            baseOptions.copy(leaderOptions = options),
        )
}

/**
 * Factory for Consul group electors.
 *
 * The supplied [ConsulEndpoint] is caller-owned and is never closed by created electors.
 */
class ConsulLeaderGroupElectorFactory(
    private val endpoint: ConsulEndpoint,
    private val baseOptions: ConsulLeaderGroupElectionOptions = ConsulLeaderGroupElectionOptions.Default,
) : LeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        ConsulLeaderGroupElector(
            endpoint,
            baseOptions.copy(leaderGroupOptions = options),
        )
}

/**
 * Factory for Consul coroutine single-leader electors.
 *
 * The supplied [ConsulEndpoint] is caller-owned and is never closed by created electors.
 */
class ConsulSuspendLeaderElectorFactory(
    private val endpoint: ConsulEndpoint,
    private val baseOptions: ConsulLeaderElectionOptions = ConsulLeaderElectionOptions.Default,
) : SuspendLeaderElectorFactory {

    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        ConsulSuspendLeaderElector(
            endpoint,
            baseOptions.copy(leaderOptions = options),
        )
}

/**
 * Factory for Consul coroutine group electors.
 *
 * The supplied [ConsulEndpoint] is caller-owned and is never closed by created electors.
 */
class ConsulSuspendLeaderGroupElectorFactory(
    private val endpoint: ConsulEndpoint,
    private val baseOptions: ConsulLeaderGroupElectionOptions = ConsulLeaderGroupElectionOptions.Default,
) : SuspendLeaderGroupElectorFactory {

    override suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        ConsulSuspendLeaderGroupElector(
            endpoint,
            baseOptions.copy(leaderGroupOptions = options),
        )
}
