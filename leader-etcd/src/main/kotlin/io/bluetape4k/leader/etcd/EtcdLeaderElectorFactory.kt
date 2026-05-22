package io.bluetape4k.leader.etcd

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
import io.etcd.jetcd.Client

/**
 * Factory for etcd single-leader electors.
 *
 * The supplied jetcd [Client] is caller-owned and is never closed by created electors.
 */
class EtcdLeaderElectorFactory(
    private val client: Client,
    private val baseOptions: EtcdLeaderElectionOptions = EtcdLeaderElectionOptions.Default,
) : LeaderElectorFactory {

    override fun create(options: LeaderElectionOptions): LeaderElector =
        EtcdLeaderElector(
            client,
            baseOptions.copy(leaderOptions = options),
        )
}

/**
 * Factory for etcd group electors.
 *
 * The supplied jetcd [Client] is caller-owned and is never closed by created electors.
 */
class EtcdLeaderGroupElectorFactory(
    private val client: Client,
    private val baseOptions: EtcdLeaderGroupElectionOptions = EtcdLeaderGroupElectionOptions.Default,
) : LeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        EtcdLeaderGroupElector(
            client,
            baseOptions.copy(leaderGroupOptions = options),
        )
}

/**
 * Factory for etcd coroutine single-leader electors.
 *
 * The supplied jetcd [Client] is caller-owned and is never closed by created electors.
 */
class EtcdSuspendLeaderElectorFactory(
    private val client: Client,
    private val baseOptions: EtcdLeaderElectionOptions = EtcdLeaderElectionOptions.Default,
) : SuspendLeaderElectorFactory {

    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        EtcdSuspendLeaderElector(
            client,
            baseOptions.copy(leaderOptions = options),
        )
}

/**
 * Factory for etcd coroutine group electors.
 *
 * The supplied jetcd [Client] is caller-owned and is never closed by created electors.
 */
class EtcdSuspendLeaderGroupElectorFactory(
    private val client: Client,
    private val baseOptions: EtcdLeaderGroupElectionOptions = EtcdLeaderGroupElectionOptions.Default,
) : SuspendLeaderGroupElectorFactory {

    override suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        EtcdSuspendLeaderGroupElector(
            client,
            baseOptions.copy(leaderGroupOptions = options),
        )
}
