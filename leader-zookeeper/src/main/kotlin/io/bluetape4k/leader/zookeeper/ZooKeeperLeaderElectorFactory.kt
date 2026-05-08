package io.bluetape4k.leader.zookeeper

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectorFactory
import org.apache.curator.framework.CuratorFramework

/**
 * [ZooKeeperLeaderElector] 팩토리입니다.
 *
 * @param client 공유 Curator 클라이언트. 호출자가 수명 관리
 * @param basePath 리더 선출 znodes 기준 경로
 */
class ZooKeeperLeaderElectorFactory(
    private val client: CuratorFramework,
    private val basePath: String = ZooKeeperLeaderElector.DEFAULT_BASE_PATH,
): LeaderElectorFactory {

    override fun create(options: LeaderElectionOptions): LeaderElector =
        ZooKeeperLeaderElector(client, basePath, options)
}
