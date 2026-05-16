package io.bluetape4k.leader.zookeeper

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import org.apache.curator.framework.CuratorFramework

/**
 * Factory for [ZooKeeperSuspendLeaderGroupElector].
 *
 * @param client Shared Curator client. Lifecycle managed by the caller.
 * @param basePath Base path for suspend leader group znodes
 */
class ZooKeeperSuspendLeaderGroupElectorFactory(
    private val client: CuratorFramework,
    private val basePath: String = ZooKeeperSuspendLeaderGroupElector.DEFAULT_BASE_PATH,
): SuspendLeaderGroupElectorFactory {

    override suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        ZooKeeperSuspendLeaderGroupElector(client, options, basePath)
}
