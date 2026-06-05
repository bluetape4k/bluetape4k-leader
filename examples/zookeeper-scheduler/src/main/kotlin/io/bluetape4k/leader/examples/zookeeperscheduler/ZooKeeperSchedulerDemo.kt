package io.bluetape4k.leader.examples.zookeeperscheduler

import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.testcontainers.infra.ZooKeeperServer
import org.apache.curator.framework.CuratorFramework
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

object ZooKeeperSchedulerDemo: KLogging() {

    @JvmStatic
    fun main(args: Array<String>) {
        val server = ZooKeeperServer.Launcher.zookeeper
        val curator = ZooKeeperServer.Launcher.getCuratorFramework(server)
        curator.start()

        try {
            check(curator.blockUntilConnected(10, TimeUnit.SECONDS)) {
                "Curator did not connect to ZooKeeper within 10 seconds"
            }
            runScenario(curator)
        } finally {
            curator.close()
        }
    }

    private fun runScenario(curator: CuratorFramework) {
        val lockName = SchedulerLockName("legacy-report-${Base58.randomString(8)}")
        val basePath = ZooKeeperSchedulerBasePath("/bluetape4k/examples/zookeeper-scheduler/${Base58.randomString(8)}")
        val nodeA = scheduler(curator, "node-a", lockName, basePath, waitTime = 2.seconds)
        val nodeB = scheduler(curator, "node-b", lockName, basePath, waitTime = 150.milliseconds)
        val firstRun = SchedulerRunId("daily-ledger-${Base58.randomString(8)}")
        val nextRun = SchedulerRunId("daily-ledger-${Base58.randomString(8)}")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val activeFuture = executor.submit<SchedulerRunReport> {
                nodeA.runOnce(firstRun) {
                    started.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    listOf("read-ledger", "write-summary")
                }
            }

            check(started.await(10, TimeUnit.SECONDS)) {
                "node-a did not acquire ZooKeeper leadership"
            }
            val skipped = nodeB.runOnce(firstRun) {
                listOf("should-not-run")
            }

            release.countDown()
            val active = activeFuture.get(10, TimeUnit.SECONDS)
            val reacquired = nodeB.runOnce(nextRun) {
                listOf("read-ledger", "write-summary")
            }

            log.info { "active=$active" }
            log.info { "skipped=$skipped" }
            log.info { "reacquired=$reacquired" }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun scheduler(
        curator: CuratorFramework,
        nodeId: String,
        lockName: SchedulerLockName,
        basePath: ZooKeeperSchedulerBasePath,
        waitTime: Duration,
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
}
