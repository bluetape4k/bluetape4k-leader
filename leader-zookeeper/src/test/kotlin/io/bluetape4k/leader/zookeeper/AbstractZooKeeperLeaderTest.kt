package io.bluetape4k.leader.zookeeper

import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.infra.ZooKeeperServer
import io.bluetape4k.utils.ShutdownQueue
import kotlinx.coroutines.delay
import org.apache.curator.framework.CuratorFramework
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractZooKeeperLeaderTest {

    companion object: KLogging() {
        val zookeeper: ZooKeeperServer by lazy { ZooKeeperServer.Launcher.zookeeper }

        val curator: CuratorFramework by lazy {
            ZooKeeperServer.Launcher.getCuratorFramework(zookeeper).also {
                it.start()
                it.blockUntilConnected(10, TimeUnit.SECONDS)
                ShutdownQueue.register { it.close() }
            }
        }
    }

    protected fun randomName(): String = "leader-test-${Base58.randomString(8)}"


    protected suspend fun randomDelay(from: Long = 10L, to: Long = 20L) {
        delay(timeMillis = Random.nextLong(from, to))
    }

    protected fun randomSleep(from: Long = 10L, to: Long = 20L) {
        Thread.sleep(Random.nextLong(from, to))
    }

}
