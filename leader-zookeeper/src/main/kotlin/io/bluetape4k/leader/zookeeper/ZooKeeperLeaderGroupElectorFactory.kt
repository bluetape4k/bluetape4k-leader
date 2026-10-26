package io.bluetape4k.leader.zookeeper

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory
import org.apache.curator.framework.CuratorFramework

/**
 * `ZooKeeperLeaderGroupElectorFactory`는 공유 ZooKeeper `CuratorFramework`와 그룹 옵션으로 그룹 elector를 생성하며, 호출마다 동일
 * client를 재사용합니다.
 *
 * @property client 각 그룹 elector가 participant ephemeral node에 사용할 `CuratorFramework`입니다.
 * @property basePath 각 그룹 lockName을 붙일 기본 ZooKeeper root path입니다.
 */
class ZooKeeperLeaderGroupElectorFactory(
    private val client: CuratorFramework,
    private val basePath: String = ZooKeeperLeaderGroupElector.DEFAULT_BASE_PATH,
): LeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        ZooKeeperLeaderGroupElector(client, options, basePath)
}
