package io.bluetape4k.leader.examples.zookeeperscheduler

import io.bluetape4k.codec.Base58
import io.bluetape4k.testcontainers.infra.ZooKeeperServer
import org.apache.curator.framework.CuratorFramework
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractZooKeeperSchedulerTest {

    companion object {
        private val zookeeper: ZooKeeperServer = ZooKeeperServer.Launcher.zookeeper
        private lateinit var curator: CuratorFramework

        @BeforeAll
        @JvmStatic
        fun startZooKeeper() {
            curator = ZooKeeperServer.Launcher.getCuratorFramework(zookeeper)
            curator.start()
            check(curator.blockUntilConnected(10, TimeUnit.SECONDS)) {
                "Curator did not connect to ZooKeeper within 10 seconds"
            }
        }

        @AfterAll
        @JvmStatic
        fun stopZooKeeper() {
            if (::curator.isInitialized) {
                curator.close()
            }
        }
    }

    protected fun scheduler(
        nodeId: String,
        lockName: SchedulerLockName = randomLockName(),
        basePath: ZooKeeperSchedulerBasePath = randomBasePath(),
        waitTime: Duration = 200.milliseconds,
    ): ZooKeeperLegacyScheduler =
        ZooKeeperLegacyScheduler(
            config = ZooKeeperSchedulerConfig(
                nodeId = SchedulerNodeId(nodeId),
                lockName = lockName,
                basePath = basePath,
                waitTime = waitTime,
                leaseTime = 10.seconds,
            ),
            curator = curator,
        )

    protected fun randomLockName(): SchedulerLockName =
        SchedulerLockName("legacy-scheduler-${Base58.randomString(8)}")

    protected fun randomBasePath(): ZooKeeperSchedulerBasePath =
        ZooKeeperSchedulerBasePath("/bluetape4k/examples/zookeeper-scheduler/test/${Base58.randomString(8)}")

    protected fun randomRunId(): SchedulerRunId =
        SchedulerRunId("run-${Base58.randomString(8)}")
}
