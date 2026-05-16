package io.bluetape4k.leader.zookeeper

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import org.apache.curator.framework.CuratorFramework

/**
 * Factory for [ZooKeeperLeaderElector].
 *
 * @param client Shared Curator client. Lifecycle managed by the caller.
 * @param basePath Base path for leader election znodes
 */
class ZooKeeperLeaderElectorFactory(
    private val client: CuratorFramework,
    private val basePath: String = ZooKeeperLeaderElector.DEFAULT_BASE_PATH,
): LeaderElectorFactory {

    override fun create(options: LeaderElectionOptions): LeaderElector =
        ZooKeeperLeaderElector(client, basePath, options)
}
