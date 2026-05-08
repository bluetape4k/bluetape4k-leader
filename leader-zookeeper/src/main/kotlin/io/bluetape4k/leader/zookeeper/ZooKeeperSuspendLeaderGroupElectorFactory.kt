package io.bluetape4k.leader.zookeeper

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import org.apache.curator.framework.CuratorFramework

/**
 * [ZooKeeperSuspendLeaderGroupElector] 팩토리입니다.
 *
 * @param client 공유 Curator 클라이언트. 호출자가 수명 관리
 * @param basePath suspend 리더 그룹 znodes 기준 경로
 */
class ZooKeeperSuspendLeaderGroupElectorFactory(
    private val client: CuratorFramework,
    private val basePath: String = ZooKeeperSuspendLeaderGroupElector.DEFAULT_BASE_PATH,
): SuspendLeaderGroupElectorFactory {

    override suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        ZooKeeperSuspendLeaderGroupElector(client, options, basePath)
}
