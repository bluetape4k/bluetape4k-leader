package io.bluetape4k.leader.examples.dynamodbexport

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionOptions
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.junit.jupiter.api.Test

class DynamoDbScheduledExportRunnerTest: AbstractDynamoDbExportTest() {

    @Test
    fun `single runner writes one export record`() = runSuspendIO {
        val batchId = randomBatchId()
        val runner = newRunner(nodeId = "node-a")

        val report = runner.runOnce(batchId) {
            "daily billing export"
        }

        val records = exportTable.recordsForBatch(batchId)
        report.status shouldBeEqualTo DynamoDbExportStatus.EXPORTED
        report.nodeId shouldBeEqualTo "node-a"
        records.size shouldBeEqualTo 1
        records.single().nodeId shouldBeEqualTo "node-a"
        records.single().summary shouldBeEqualTo "daily billing export"
    }

    @Test
    fun `contending node skips while leader holds scheduled export lock`() = runSuspendIO {
        val batchId = randomBatchId()
        val lockName = randomLockName()
        val keyPrefix = randomKeyPrefix()
        val leader = newRunner(
            nodeId = "node-a",
            lockName = lockName,
            keyPrefix = keyPrefix,
            leaderOptions = LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 5.seconds),
        )
        val contender = newRunner(
            nodeId = "node-b",
            lockName = lockName,
            keyPrefix = keyPrefix,
            leaderOptions = LeaderElectionOptions(waitTime = 150.milliseconds, leaseTime = 5.seconds),
        )
        val leaderStarted = CompletableDeferred<Unit>()
        val releaseLeader = CompletableDeferred<Unit>()

        val leaderJob = async {
            leader.runOnce(batchId) {
                leaderStarted.complete(Unit)
                releaseLeader.await()
                "node-a export"
            }
        }

        leaderStarted.await()
        val skipped = contender.runOnce(batchId) {
            "node-b export"
        }

        releaseLeader.complete(Unit)
        val leaderReport = leaderJob.await()
        val records = exportTable.recordsForBatch(batchId)

        leaderReport.status shouldBeEqualTo DynamoDbExportStatus.EXPORTED
        skipped.status shouldBeEqualTo DynamoDbExportStatus.SKIPPED
        records.size shouldBeEqualTo 1
        records.single().nodeId shouldBeEqualTo "node-a"
    }

    @Test
    fun `released lock allows next scheduled export batch`() = runSuspendIO {
        val lockName = randomLockName()
        val keyPrefix = randomKeyPrefix()
        val firstBatch = randomBatchId()
        val secondBatch = randomBatchId()
        val nodeA = newRunner(nodeId = "node-a", lockName = lockName, keyPrefix = keyPrefix)
        val nodeB = newRunner(nodeId = "node-b", lockName = lockName, keyPrefix = keyPrefix)

        val first = nodeA.runOnce(firstBatch) { "first export" }
        val second = nodeB.runOnce(secondBatch) { "second export" }

        first.status shouldBeEqualTo DynamoDbExportStatus.EXPORTED
        second.status shouldBeEqualTo DynamoDbExportStatus.EXPORTED
        exportTable.recordsForBatch(firstBatch).single().nodeId shouldBeEqualTo "node-a"
        exportTable.recordsForBatch(secondBatch).single().nodeId shouldBeEqualTo "node-b"
    }

    @Test
    fun `runner validates required fields`() = runSuspendIO {
        assertFailsWith<IllegalArgumentException> {
            DynamoDbExportRunnerOptions(nodeId = " ", lockName = "lock")
        }
        assertFailsWith<IllegalArgumentException> {
            DynamoDbExportRunnerOptions(nodeId = "node", lockName = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            newRunner(nodeId = "node-a").runOnce(" ") { "summary" }
        }
        assertFailsWith<IllegalArgumentException> {
            newRunner(nodeId = "node-a").runOnce(randomBatchId()) { " " }
        }
    }
}
