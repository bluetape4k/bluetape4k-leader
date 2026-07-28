package io.bluetape4k.leader.zookeeper

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import org.apache.curator.framework.CuratorFramework

/**
 * `ZooKeeperLeaderElectorFactory`는 ZooKeeper backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property client ZooKeeper backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property basePath ZooKeeper backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class ZooKeeperLeaderElectorFactory(
    private val client: CuratorFramework,
    private val basePath: String = ZooKeeperLeaderElector.DEFAULT_BASE_PATH,
): LeaderElectorFactory {

    override fun create(options: LeaderElectionOptions): LeaderElector =
        ZooKeeperLeaderElector(client, basePath, options)
}
