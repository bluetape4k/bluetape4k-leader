package io.bluetape4k.leader.examples.dynamodbexport

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.coroutines.support.log
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamoDbScheduledExportRunnerTest: AbstractDynamoDbExportTest() {

    companion object: KLoggingChannel()

    @Test
    fun `single runner writes one export record`() = runSuspendIO {
        val batchId = randomBatchId()
        val runner = newRunner(nodeId = "node-a")

        val report = runner.runOnce(batchId) {
            "daily billing export"
        }
        log.debug { "report=$report" }
        report.status shouldBeEqualTo DynamoDbExportStatus.EXPORTED
        report.nodeId shouldBeEqualTo "node-a"

        val records = exportTable.recordsForBatch(batchId)

        records shouldHaveSize 1
        val record = records.single()
        log.debug { "record=$record" }
        record.nodeId shouldBeEqualTo "node-a"
        record.summary shouldBeEqualTo "daily billing export"
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
        }.log("Leader")

        leaderStarted.await()
        val skipped = contender.runOnce(batchId) {
            "node-b export"
        }
        log.debug { "skipped=$skipped" }
        skipped.status shouldBeEqualTo DynamoDbExportStatus.SKIPPED

        releaseLeader.complete(Unit)
        val leaderReport = leaderJob.await()
        log.debug { "leaderRecord=$leaderReport" }
        leaderReport.status shouldBeEqualTo DynamoDbExportStatus.EXPORTED


        val records = exportTable.recordsForBatch(batchId)
        records shouldHaveSize 1
        log.debug { "record=${records.single()}" }
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

        log.debug { "first=$first" }
        log.debug { "second=$second" }
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
