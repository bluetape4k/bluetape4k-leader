package io.bluetape4k.leader.zookeeper.internal

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.imps.CuratorFrameworkState
import org.apache.curator.utils.ZKPaths

internal object ZooKeeperOwnershipProbe {

    fun isLiveNode(client: CuratorFramework, nodePath: String): Boolean {
        if (client.state != CuratorFrameworkState.STARTED) return false
        if (!client.zookeeperClient.isConnected) return false
        return client.checkExists().forPath(nodePath) != null
    }

    fun leaseNodePath(slotKey: String, leaseNodeName: String): String =
        ZKPaths.makePath(ZKPaths.makePath(slotKey, LEASES_NODE), leaseNodeName)

    private const val LEASES_NODE = "leases"
}
