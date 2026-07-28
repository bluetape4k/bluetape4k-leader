package io.bluetape4k.leader.zookeeper.internal

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.locks.InterProcessMutex

/**
 * `ZooKeeperOwnedInterProcessMutex`는 현재 thread가 Curator lock에서 확보한 ZooKeeper path를 노출하는 내부 mutex입니다.
 *
 * @property client ZooKeeper backend와 통신하는 Curator client입니다.
 * @property path Curator `InterProcessMutex`가 lock node를 생성하고 감시하는 ZooKeeper path입니다.
 */
internal class ZooKeeperOwnedInterProcessMutex(
    client: CuratorFramework,
    path: String,
): InterProcessMutex(client, path) {

    /**
     * 현재 thread가 보유한 Curator lock path를 반환합니다.
     */
    fun currentThreadLockPath(): String? = getLockPath()
}
