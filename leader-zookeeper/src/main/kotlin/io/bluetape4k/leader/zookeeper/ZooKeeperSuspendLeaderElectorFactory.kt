package io.bluetape4k.leader.zookeeper

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import org.apache.curator.framework.CuratorFramework

/**
 * Factory for [ZooKeeperSuspendLeaderElector].
 *
 * @param client Shared Curator client. Lifecycle managed by the caller.
 * @param basePath Base path for suspend leader election znodes
 */
class ZooKeeperSuspendLeaderElectorFactory(
    private val client: CuratorFramework,
    private val basePath: String = ZooKeeperSuspendLeaderElector.DEFAULT_BASE_PATH,
): SuspendLeaderElectorFactory {

    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        ZooKeeperSuspendLeaderElector(client, basePath, options)
}
