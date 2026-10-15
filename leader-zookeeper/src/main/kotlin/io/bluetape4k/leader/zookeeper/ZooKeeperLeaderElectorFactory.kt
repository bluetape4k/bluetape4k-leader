package io.bluetape4k.leader.zookeeper

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import org.apache.curator.framework.CuratorFramework

/**
 * `ZooKeeperLeaderElectorFactory`는 공유 ZooKeeper `CuratorFramework`와 단일 선출 옵션으로 elector를 생성합니다.
 *
 * @property client 각 single elector가 session과 ephemeral lock node에 사용할 `CuratorFramework`입니다.
 * @property basePath 각 lockName을 붙일 기본 ZooKeeper root path입니다.
 */
class ZooKeeperLeaderElectorFactory(
    private val client: CuratorFramework,
    private val basePath: String = ZooKeeperLeaderElector.DEFAULT_BASE_PATH,
): LeaderElectorFactory {

    override fun create(options: LeaderElectionOptions): LeaderElector =
        ZooKeeperLeaderElector(client, basePath, options)
}
