package io.bluetape4k.leader.zookeeper

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import org.apache.curator.framework.CuratorFramework

/**
 * [ZooKeeperSuspendLeaderElector] 팩토리입니다.
 *
 * @param client 공유 Curator 클라이언트. 호출자가 수명 관리
 * @param basePath suspend 리더 선출 znodes 기준 경로
 */
class ZooKeeperSuspendLeaderElectorFactory(
    private val client: CuratorFramework,
    private val basePath: String = ZooKeeperSuspendLeaderElector.DEFAULT_BASE_PATH,
): SuspendLeaderElectorFactory {

    override suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        ZooKeeperSuspendLeaderElector(client, basePath, options)
}
