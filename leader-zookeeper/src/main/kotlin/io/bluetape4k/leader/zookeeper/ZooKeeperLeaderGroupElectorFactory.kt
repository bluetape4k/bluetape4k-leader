package io.bluetape4k.leader.zookeeper

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory
import org.apache.curator.framework.CuratorFramework

/**
 * [ZooKeeperLeaderGroupElector] 팩토리입니다.
 *
 * @param client 공유 Curator 클라이언트. 호출자가 수명 관리
 * @param basePath 리더 그룹 znodes 기준 경로
 */
class ZooKeeperLeaderGroupElectorFactory(
    private val client: CuratorFramework,
    private val basePath: String = ZooKeeperLeaderGroupElector.DEFAULT_BASE_PATH,
): LeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        ZooKeeperLeaderGroupElector(client, options, basePath)
}
