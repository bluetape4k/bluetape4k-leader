package io.bluetape4k.leader.examples.zookeeperscheduler

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ZooKeeperLegacySchedulerTest: AbstractZooKeeperSchedulerTest() {

    @Test
    fun `single scheduler executes legacy job`() {
        val runId = randomRunId()
        val node = scheduler(nodeId = "node-a")

        val report = node.runOnce(runId) {
            listOf("load-input", "write-output")
        }

        report.nodeId shouldBeEqualTo SchedulerNodeId("node-a")
        report.scheduleId shouldBeEqualTo runId
        report.status shouldBeEqualTo SchedulerRunStatus.EXECUTED
        report.completedSteps shouldBeEqualTo listOf("load-input", "write-output")
    }

    @Test
    fun `competing scheduler skips while leader holds ZooKeeper lock`() {
        val lockName = randomLockName()
        val basePath = randomBasePath()
        val runId = randomRunId()
        val nodeA = scheduler(
            nodeId = "node-a",
            lockName = lockName,
            basePath = basePath,
            waitTime = 2.seconds,
        )
        val nodeB = scheduler(
            nodeId = "node-b",
            lockName = lockName,
            basePath = basePath,
            waitTime = 150.milliseconds,
        )
        val nodeBExecutions = AtomicInteger(0)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val activeFuture = executor.submit<SchedulerRunReport> {
                nodeA.runOnce(runId) {
                    started.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    listOf("node-a-step")
                }
            }

            started.await(10, TimeUnit.SECONDS) shouldBeEqualTo true
            val skipped = nodeB.runOnce(runId) {
                nodeBExecutions.incrementAndGet()
                listOf("node-b-step")
            }

            release.countDown()
            val active = activeFuture.get(10, TimeUnit.SECONDS)

            active.status shouldBeEqualTo SchedulerRunStatus.EXECUTED
            active.nodeId shouldBeEqualTo SchedulerNodeId("node-a")
            active.completedSteps shouldBeEqualTo listOf("node-a-step")
            skipped.status shouldBeEqualTo SchedulerRunStatus.SKIPPED
            skipped.nodeId shouldBeEqualTo SchedulerNodeId("node-b")
            skipped.completedSteps shouldBeEqualTo emptyList()
            nodeBExecutions.get() shouldBeEqualTo 0
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `released ZooKeeper lock allows next scheduler run`() {
        val lockName = randomLockName()
        val basePath = randomBasePath()
        val firstRun = randomRunId()
        val secondRun = randomRunId()
        val nodeA = scheduler(nodeId = "node-a", lockName = lockName, basePath = basePath)
        val nodeB = scheduler(nodeId = "node-b", lockName = lockName, basePath = basePath)

        val first = nodeA.runOnce(firstRun) { listOf("node-a-step") }
        val second = nodeB.runOnce(secondRun) { listOf("node-b-step") }

        first.status shouldBeEqualTo SchedulerRunStatus.EXECUTED
        first.nodeId shouldBeEqualTo SchedulerNodeId("node-a")
        second.status shouldBeEqualTo SchedulerRunStatus.EXECUTED
        second.nodeId shouldBeEqualTo SchedulerNodeId("node-b")
    }

    @Test
    fun `scheduler validates required fields and completed steps`() {
        assertFailsWith<IllegalArgumentException> {
            SchedulerNodeId(" ")
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulerLockName(" ")
        }
        assertFailsWith<IllegalArgumentException> {
            ZooKeeperSchedulerBasePath(" ")
        }
        assertFailsWith<IllegalArgumentException> {
            SchedulerRunId(" ")
        }
        assertFailsWith<IllegalArgumentException> {
            scheduler(nodeId = "node-a", waitTime = 200.milliseconds)
                .runOnce(randomRunId()) {
                    listOf("prepare", " ")
                }
        }
    }
}
