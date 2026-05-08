package io.bluetape4k.leader.zookeeper

import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.infra.ZooKeeperServer
import io.bluetape4k.utils.ShutdownQueue
import org.apache.curator.framework.CuratorFramework
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractZooKeeperLeaderTest {

    companion object: KLogging() {
        val zookeeper: ZooKeeperServer = ZooKeeperServer.Launcher.zookeeper

        val curator: CuratorFramework by lazy {
            ZooKeeperServer.Launcher.getCuratorFramework(zookeeper).also {
                it.start()
                it.blockUntilConnected(10, TimeUnit.SECONDS)
                ShutdownQueue.register { it.close() }
            }
        }
    }

    fun randomName(): String = "leader-test-${Base58.randomString(8)}"
}
