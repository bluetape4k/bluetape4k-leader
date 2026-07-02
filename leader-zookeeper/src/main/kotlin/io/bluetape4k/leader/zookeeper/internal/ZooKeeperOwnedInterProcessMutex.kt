package io.bluetape4k.leader.zookeeper.internal

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.locks.InterProcessMutex

internal class ZooKeeperOwnedInterProcessMutex(
    client: CuratorFramework,
    path: String,
): InterProcessMutex(client, path) {

    fun currentThreadLockPath(): String? = getLockPath()
}
