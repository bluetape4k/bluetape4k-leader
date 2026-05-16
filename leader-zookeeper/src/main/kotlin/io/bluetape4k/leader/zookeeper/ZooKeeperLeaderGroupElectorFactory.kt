package io.bluetape4k.leader.zookeeper

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory
import org.apache.curator.framework.CuratorFramework

/**
 * Factory for [ZooKeeperLeaderGroupElector].
 *
 * @param client Shared Curator client. Lifecycle managed by the caller.
 * @param basePath Base path for leader group znodes
 */
class ZooKeeperLeaderGroupElectorFactory(
    private val client: CuratorFramework,
    private val basePath: String = ZooKeeperLeaderGroupElector.DEFAULT_BASE_PATH,
): LeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        ZooKeeperLeaderGroupElector(client, options, basePath)
}
