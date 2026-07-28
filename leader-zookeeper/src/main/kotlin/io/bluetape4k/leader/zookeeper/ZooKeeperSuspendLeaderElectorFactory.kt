package io.bluetape4k.leader.zookeeper

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import org.apache.curator.framework.CuratorFramework

/**
 * `ZooKeeperSuspendLeaderElectorFactory`는 ZooKeeper backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property client ZooKeeper backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property basePath ZooKeeper backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class ZooKeeperSuspendLeaderElectorFactory(
    private val client: CuratorFramework,
    private val basePath: String = ZooKeeperSuspendLeaderElector.DEFAULT_BASE_PATH,
): SuspendLeaderElectorFactory {

    override suspend fun create(options: LeaderElectionOptions): ZooKeeperSuspendLeaderElector =
        ZooKeeperSuspendLeaderElector(client, basePath, options)
}
