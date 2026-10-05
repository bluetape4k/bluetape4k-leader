package io.bluetape4k.leader.examples.zookeeperscheduler

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.get
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ZooKeeperLegacySchedulerTest: AbstractZooKeeperSchedulerTest() {

    companion object: KLogging()

    @Test
    fun `single scheduler executes legacy job`() {
        val runId = randomRunId()
        val node = scheduler(nodeId = "node-a")

        val report = node.runOnce(runId) {
            listOf("load-input", "write-output")
        }

        log.debug { "report=$report" }
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
                    release.await(10.seconds)
                    listOf("node-a-step")
                }
            }

            started.await(10.seconds).shouldBeTrue()

            val skipped = nodeB.runOnce(runId) {
                nodeBExecutions.incrementAndGet()
                listOf("node-b-step")
            }
            log.debug { "skipped=$skipped" }

            release.countDown()

            val active = activeFuture.get(10.seconds)

            log.debug { "active=$active" }
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

        log.debug { "first=$first" }
        log.debug { "second=$second" }

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
